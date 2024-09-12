package cetus.transforms.LLMTransformations;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cetus.analysis.LoopTools;
import cetus.exec.CetusParser;
import cetus.exec.CommandLineOptionSet;
import cetus.exec.Driver;
import cetus.hir.Annotatable;
import cetus.hir.AnnotationStatement;
import cetus.hir.DFIterator;
import cetus.hir.ForLoop;
import cetus.hir.Loop;
import cetus.hir.PragmaAnnotation;
import cetus.hir.PrintTools;
import cetus.hir.Program;
import cetus.hir.Statement;
import cetus.hir.Symbol;
import cetus.hir.SymbolTools;
import cetus.hir.TranslationUnit;
import cetus.hir.Traversable;
import cetus.transforms.AnnotationParser;
import cetus.transforms.TransformPass;
import cetus.transforms.LLMTransformations.LLMTransformers.BasicModelParameters;
import cetus.transforms.LLMTransformations.LLMTransformers.CodeLLamaInputsOnlyTransformer;
import cetus.transforms.LLMTransformations.LLMTransformers.CodeLLamaTransformer;
import cetus.transforms.LLMTransformations.LLMTransformers.GPTTransformer;
import cetus.transforms.LLMTransformations.LLMTransformers.LLMResponse;
import cetus.transforms.LLMTransformations.LLMTransformers.LLMTransformer;

public class LLMOptimizationPass extends TransformPass {

    public static final String PASS_NAME = "LLM-Optimizations";
    public static final String PASS_CMD_OPTION = "llm-opt";
    public static final String DESCRIPTION = "experimental feature to update code sections by using LLMs. Use this attribute to list the llms to use separate by comma. default: gpt-4,codellama";

    public static final String PASS_FOLDER_CMD_OPTION = "llm-results-folder";
    public static final String PASS_FOLDER_CMD_DESCRIPTION = "Select folder to put results";

    public static final String PASS_TEMPERATURE_CMD_OPTION = "llm-opt-temp";
    public static final String PASS_TEMPERATURE_CMD_DESCRIPTION = "Select models temperature. 0.0-1.0";

    public static final String PASS_TOP_P_CMD_OPTION = "llm-opt-top-p";
    public static final String PASS_TOP_P_CMD_DESCRIPTION = "Set models top_p parameter. 0.0-1.0";

    public static final String PASS_PROMPTS_CMD_OPTION = "llm-prompts";
    public static final String PASS_PROMPTS_CMD_DESCRIPTION = "Select which prompts you want to use. Default: instructions,cot";

    private String instructionsPrompt;
    private String cotPrompt;

    protected CommandLineOptionSet commandLineOptionSet;

    private HashMap<String, String> optimizedCodes = new HashMap<>();

    public LLMOptimizationPass(Program program) {
        super(program);
    }

    public LLMOptimizationPass(Program program, CommandLineOptionSet options) {
        super(program);
        this.commandLineOptionSet = options;

    }

    @Override
    public String getPassName() {
        return "LLM-Optimizations";
    }

    private String loadPrompt(String promptFilePath) throws Exception {
        File promptFile = new File(promptFilePath);
        BufferedReader br = new BufferedReader(new FileReader(promptFile));
        String prompt = "";
        String line = null;
        while ((line = br.readLine()) != null) {
            prompt += line + System.lineSeparator();
        }
        br.close();
        return prompt;
    }

    private void loadPrompts() throws Exception {

        cotPrompt = """
                Given the C program below, think of a way how to optimize its performance using OpenMP.
                #### Program:
                {{src-code}}

                #### Full optimized version:
                {{mask}}
                Summarize the way you found in a single paragraph. Then, separately provide the code snippet of the code using ```c{{src-code}}```
                    """;

        instructionsPrompt = """
                Given the C program below, improve its performance using OpenMP.
                #### Program:
                {{src-code}}

                #### Full optimized version:
                {{mask}}
                Only provide the code snippets, do not provide more than that. To provide the code snippet use ```c{{src-code}}```
                """;
        try {
            cotPrompt = loadPrompt(System.getenv("CETUS_COT_PROMPT"));
            instructionsPrompt = loadPrompt(System.getenv("CETUS_INSTRUCTIONS_PROMPT"));
        } catch (Exception e) {
            System.err.println("ISSUE_LOADING_PROMPTS");
            System.err.println(e.getMessage());
            // e.printStackTrace();
        }

    }

    protected LLMTargetSection getLLMTarget(Traversable t) {
        return getLLMTarget(t, "experimental section start", "experimental section stop");

    }

    protected LLMTargetSection getLLMTarget(Traversable t, String startPragma, String endPragma) {
        LLMTargetSection target = new LLMTargetSection(t);

        // found position of startPragma in the t obj
        boolean collects = false;

        for (int i = 0; i < t.getChildren().size(); i++) {
            Traversable child = t.getChildren().get(i);

            if (child instanceof AnnotationStatement) {
                AnnotationStatement a = (AnnotationStatement) child;
                a.getChildren();
                if (a.toString().contains(startPragma)) {
                    collects = true;
                    continue;
                } else if (a.toString().contains(endPragma)) {
                    collects = false;
                    break;

                }

            }

            else if (collects) {
                // target.addStatement(((Statement) child).clone(true));
                target.addStatement(((Statement) child));

            }
        }

        return target;

    }

    protected List<LLMTargetSection> getTargetSections() {
        List<LLMTargetSection> targetSections = new ArrayList<LLMTargetSection>();
        DFIterator<Annotatable> iter = new DFIterator<Annotatable>(program, Annotatable.class);
        while (iter.hasNext()) {
            Annotatable at = iter.next();
            List<PragmaAnnotation> pragmas = at.getAnnotations(PragmaAnnotation.class);
            for (int i = 0; i < pragmas.size(); i++) {
                PragmaAnnotation pragma = pragmas.get(i);
                for (Object values : pragma.values()) {
                    if (values.toString().contains("experimental section start")) {
                        targetSections.add(getLLMTarget(at.getParent()));
                    }
                }

            }
        }

        return targetSections;
    }

    protected Program parseProgram(LLMTargetSection programSection, LLMTransformer transformer) throws Exception {
        // save optized code in a tempfile using buffered writer

        File tempFile = File.createTempFile("tempfile_" + transformer.getModel(), ".c");
        // write the optimized code to the temp file
        BufferedWriter writer = new BufferedWriter(new FileWriter(tempFile));
        // Add the variables initialization to the file
        // Get program symbol table
        // Find for every traversable in the target section for the matching variable in
        // the symbol table
        // If found, add the variable declaration to the file
        // If not found, add the variable declaration to the file

        List<String> optimizedCodes = new ArrayList<>();
        List<Loop> loops = extractLoops(programSection);
        for (int i = 0; i < loops.size(); i++) {
            String key = transformer.getModel() + "-loop_" + i;
            optimizedCodes.add(this.optimizedCodes.get(key));
        }

        Set<String> symbols = new HashSet<>();

        // for (Symbol symbol :
        // SymbolTools.getGlobalSymbols(programSection.getOriginalParent())) {

        // String declaration = symbol.getDeclaration().toString();
        // if (!declaration.endsWith(";")) {
        // declaration += ";";
        // }
        // if (symbols.contains(declaration)) {
        // continue;
        // }
        // symbols.add(declaration);
        // // System.out.println(declaration);
        // writer.write(declaration + "\n");
        // }

        writer.write("int main() {\n");
        for (Symbol symbol : SymbolTools.getLocalSymbols(programSection.getOriginalParent())) {
            String declaration = symbol.getDeclaration().toString().trim();
            if (!declaration.endsWith(";")) {
                declaration += ";";
            }
            if (symbols.contains(declaration)) {
                continue;
            }
            symbols.add(declaration);
            // System.out.println(declaration);
            writer.write(declaration + "\n");
        }
        writer.write("#pragma llm_optimized start\n");
        for (int i = 0; i < optimizedCodes.size(); i++) {
            String optimizedCode = optimizedCodes.get(i);
            writer.write(optimizedCode + "\n");
        }
        writer.write("#pragma llm_optimized stop\n");
        writer.write("return 0;\n}\n");
        writer.close();

        try {
            Program optimizedSectionProgram = new Program();
            Class<?> class_parser = GPTTransformer.class.getClassLoader().loadClass(
                    commandLineOptionSet.getValue("parser"));
            CetusParser cparser = (CetusParser) class_parser.getConstructor().newInstance();
            TranslationUnit tu = cparser.parseFile(tempFile.getAbsolutePath(), commandLineOptionSet);

            optimizedSectionProgram.addTranslationUnit(tu);
            // It is more natural to include these two steps in this method.
            // Link IDExpression => Symbol object for faster future access.
            SymbolTools.linkSymbol(optimizedSectionProgram);
            // Convert the IR to a new one with improved annotation support
            TransformPass.run(new AnnotationParser(optimizedSectionProgram));
            if (PrintTools.getVerbosity() >= 3) {

                System.out.println(optimizedSectionProgram);
            }
            return optimizedSectionProgram;
        } catch (Exception e) {
            // TODO: handle exception
            e.printStackTrace();
            throw e;

        } finally {
            // tempFile.delete();
        }
    }

    private String getModelFolder(String model) {
        String newDirName = Driver.getOptionValue(PASS_FOLDER_CMD_OPTION);
        String userDir = System.getProperty("user.dir");
        String dirStr = userDir + "/llm-optimizations-outputs/" + newDirName;
        String modelDir = dirStr + "/" + model;
        return modelDir;
    }

    private String getCotFolder(String model) {
        String cotDirStr = getModelFolder(model) + "/cot";
        File cotDir = new File(cotDirStr);
        if (!cotDir.exists()) {
            cotDir.mkdirs();
        }

        return cotDirStr;
    }

    private String getInstructionFolder(String model) {

        String instructionsDirStr = getModelFolder(model) + "/instruction";

        File instructionsDir = new File(instructionsDirStr);
        if (!instructionsDir.exists()) {
            instructionsDir.mkdirs();
        }

        return instructionsDirStr;

    }

    private void saveInputsOnly(String optimizationId, String parentFolder, LLMResponse llmResponse) {
        String optimizationDirStr = parentFolder + "/" + optimizationId;
        File optimizationDir = new File(optimizationDirStr);
        if (!optimizationDir.exists()) {
            optimizationDir.mkdirs();
        }

        File file = null;

        // Loop until a unique filename is found
        int counter = 1;
        while (true) {
            String fileName = "input_" + counter + ".txt";
            file = new File(optimizationDir + "/" + fileName);

            // Check if the file already exists
            if (!file.exists()) {
                try {
                    // Create the file
                    if (file.createNewFile()) {

                        BufferedWriter bw = new BufferedWriter(new FileWriter(file));
                        String code = llmResponse.getContent();

                        bw.write(code);
                        bw.close();

                        String llmResponseFileName = "input_" + counter + ".json";
                        File llmResponseFile = new File(optimizationDir + "/" + llmResponseFileName);
                        bw = new BufferedWriter(new FileWriter(llmResponseFile));

                        bw.write(llmResponse.getResponse().toString());
                        bw.close();

                    } else {
                        System.out.println("Failed to create the file: " + fileName);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
                break; // Exit the loop once a unique filename is found and the file is created
            }

            counter++; // Increment the counter to generate a new filename
        }

    }

    private void saveCode(String optimizationId, String parentFolder, LLMResponse llmResponse) {

        if (llmResponse.getModel().equals(CodeLLamaInputsOnlyTransformer.MODEL)) {
            saveInputsOnly(optimizationId, parentFolder, llmResponse);
            return;
        }
        String optimizationDirStr = parentFolder + "/" + optimizationId;
        File optimizationDir = new File(optimizationDirStr);
        if (!optimizationDir.exists()) {
            optimizationDir.mkdirs();
        }

        File file = null;

        // Loop until a unique filename is found
        int counter = 1;
        while (true) {
            String fileName = "code_" + counter + ".c";
            file = new File(optimizationDir + "/" + fileName);

            // Check if the file already exists
            if (!file.exists()) {
                try {
                    // Create the file
                    if (file.createNewFile()) {

                        if (PrintTools.getVerbosity() >= 3) {
                            System.out.println("File created successfully: " + fileName);
                        }
                        BufferedWriter bw = new BufferedWriter(new FileWriter(file));
                        String code = extractCodeSnippet(llmResponse.getContent());

                        bw.write(code);
                        bw.close();

                        String llmResponseFileName = "response_" + counter + ".json";
                        File llmResponseFile = new File(optimizationDir + "/" + llmResponseFileName);
                        bw = new BufferedWriter(new FileWriter(llmResponseFile));

                        bw.write(llmResponse.toJSONString());
                        bw.close();

                        String chatFilename = "chat_" + counter + ".txt";
                        File chatFile = new File(optimizationDir + "/" + chatFilename);
                        bw = new BufferedWriter(new FileWriter(chatFile));

                        bw.write("model" + llmResponse.getModel() + "\n");
                        bw.write("parameters: " + llmResponse.getModelParameters().toJSONString() + "\n\n");
                        bw.write(llmResponse.getPrompt() + "\n\n");

                        String content = llmResponse.getContent();
                        if (content == null || content.trim().isBlank() || content.trim().isEmpty()) {
                            content = "EMPTY. Check experiments";
                        }
                        bw.write("ANSWER:\n");
                        bw.write(content + "\n");
                        bw.close();
                    } else {
                        System.out.println("Failed to create the file: " + fileName);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
                break; // Exit the loop once a unique filename is found and the file is created
            }

            counter++; // Increment the counter to generate a new filename
        }
    }

    // public static List<String> extractCodeSnippets(String text) {
    // List<String> codeSnippets = new ArrayList<>();

    // // Regular expression to match code snippets enclosed in triple backticks
    // with language specifier 'c'
    // Pattern pattern = Pattern.compile("```c\\s*(.*?)\\s*```", Pattern.DOTALL);
    // Matcher matcher = pattern.matcher(text);

    // while (matcher.find()) {
    // codeSnippets.add(matcher.group(1));
    // }

    // return codeSnippets;
    // }

    protected String extractCodeSnippet(String content) {
        String optimizedCode = "";
        // if pattern ```code``` is found, then get the content inside the code block or
        // else use the whole content
        String patternStr = "";
        Pattern pattern = null;
        if (content.contains("```C")) {
            patternStr = "```C\\s*(.*?)\\s*```";
        } else if (content.contains("```c")) {
            patternStr = "```c\\s*(.*?)\\s*```";
        } else if (content.contains("```code")) {
            patternStr = "```code\\s*(.*?)\\s*```";
        } else if (content.contains("```cpp")) {
            patternStr = "```cpp\\s*(.*?)\\s*```";
        }

        if (!patternStr.isBlank() && !patternStr.isEmpty()) {
            List<String> codeSnippets = new ArrayList<>();
            pattern = Pattern.compile(patternStr, Pattern.DOTALL);
            Matcher matcher = pattern.matcher(content);
            while (matcher.find()) {
                codeSnippets.add(matcher.group(1));
            }

            for (String codeSnippet : codeSnippets) {
                optimizedCode += codeSnippet + "\n";
            }
        } else {
            optimizedCode = content;
        }

        // System.out.println("Response status code: " + optimizedCode);
        return optimizedCode;
    }

    protected void optimize(String programSection, String folder, String prompt, LLMTransformer transformer,
            BasicModelParameters modelParameters) throws Exception {
        optimize("full_code", programSection, folder, prompt, transformer, modelParameters);
    }

    protected void optimize(String optimizationId, String programSection, String folder, String prompt,
            LLMTransformer transformer, BasicModelParameters modelParameters) throws Exception {

        try {
            LLMResponse response = transformer.transform(prompt, programSection, modelParameters);
            this.optimizedCodes.put(transformer.getModel() + "-" + optimizationId, response.getContent());
            saveCode(optimizationId, folder, response);

        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    private List<Loop> extractLoops(LLMTargetSection programSection) {
        List<Loop> loops = new ArrayList<>();
        for (Traversable traversable : programSection.getSection()) {
            if (traversable instanceof ForLoop) {
                loops.add((ForLoop) traversable);
            } else if (traversable instanceof Loop) {
                loops.add((Loop) traversable);
            }
        }

        return loops;
    }

    public List<LLMTransformer> getTransformers() {
        List<LLMTransformer> transformers = new ArrayList<>();

        String transformersOptionsStr = commandLineOptionSet.getValue(PASS_CMD_OPTION);
        if (transformersOptionsStr == null || transformersOptionsStr.isEmpty() || transformersOptionsStr.isBlank()) {
            transformersOptionsStr = "gpt,codellama";
        }

        transformersOptionsStr = transformersOptionsStr.toLowerCase();
        String[] transformersOptions = transformersOptionsStr.split(",");

        for (String option : transformersOptions) {
            if (option.equals("gpt")) {
                transformers.add(new GPTTransformer());
            }
            if (option.equals("codellama-inputs")) {
                transformers.add(new CodeLLamaInputsOnlyTransformer());
            }
            if (option.equals("codellama")) {
                transformers.add(new CodeLLamaTransformer());
            }
        }

        return transformers;
    }

    private void startOptimizations() throws Exception {
        loadPrompts();

        Double temperature = 0.7;
        String temperatureStr = commandLineOptionSet
                .getValue(PASS_TEMPERATURE_CMD_OPTION);
        if (temperatureStr != null && !temperatureStr.isEmpty() && !temperatureStr.isBlank()) {
            temperature = Double.parseDouble(temperatureStr);
        }

        String topPStr = commandLineOptionSet
                .getValue(PASS_TOP_P_CMD_OPTION);

        Double topP = 0.1;
        if (topPStr != null && !topPStr.isEmpty() && !topPStr.isBlank()) {
            topP = Double.parseDouble(topPStr);
        }

        if (PrintTools.getVerbosity() >= 2) {
            System.out.println("TempStr: " + temperatureStr + " - top_p_str: " + topPStr + "\n");
            System.out.println("Temp: " + temperature + " - top_p: " + topP + "\n");
        }

        BasicModelParameters modelParameters = new BasicModelParameters(temperature,
                BasicModelParameters.MAX_NEW_TOKENS, topP);

        List<LLMTransformer> transformers = getTransformers();

        int numThreads = 4;
        ExecutorService executorService = Executors.newFixedThreadPool(numThreads);

        List<CompletableFuture<Void>> allTasks = new ArrayList<>();

        try {

            String temperatureSuffix = "" + (int) (modelParameters.getTemperature() * 100);
            String topPSuffix = "" + (int) (modelParameters.getTopP() * 100);

            String promptsOptionStr = commandLineOptionSet.getValue(PASS_PROMPTS_CMD_OPTION);
            if (promptsOptionStr == null || promptsOptionStr.isEmpty() || promptsOptionStr.isBlank()) {
                promptsOptionStr = "instructions,cot";
            }

            promptsOptionStr = promptsOptionStr.toLowerCase();

            boolean shouldCot = promptsOptionStr.contains("cot");
            boolean shouldInstruct = promptsOptionStr.contains("instructions");

            List<LLMTargetSection> targetSections = getTargetSections();

            for (LLMTargetSection section : targetSections) {

                for (LLMTransformer transformer : transformers) {
                    String instructionsFolder = getInstructionFolder(
                            transformer.getModel() + "_" + temperatureSuffix + "_" + topPSuffix);
                    String cotFolder = getCotFolder(
                            transformer.getModel() + "_" + temperatureSuffix + "_" + topPSuffix);

                    if (shouldInstruct) {
                        List<CompletableFuture<Void>> tasksInstructions = callCodeTransformation(executorService,
                                section,
                                transformer,
                                modelParameters, instructionsFolder, instructionsPrompt);

                        for (CompletableFuture<Void> task : tasksInstructions) {
                            allTasks.add(task);
                        }
                    }

                    if (shouldCot) {
                        List<CompletableFuture<Void>> tasksCot = callCodeTransformation(executorService, section,
                                transformer,
                                modelParameters, cotFolder, cotPrompt);

                        for (CompletableFuture<Void> task : tasksCot) {
                            allTasks.add(task);
                        }
                    }
                    
                }

                allTasks.forEach(CompletableFuture::join);

                // for (LLMTransformer transformer : transformers) {
                // Program program = parseProgram(section, transformer);

                // }

            }

            // CaRV try. The following code was made to udnerstand how one could wait for
            // the carv process to finish
            // CompletableFuture<Void> combinedTask = CompletableFuture
            // .allOf(allTasks.toArray(new CompletableFuture[allTasks.size()]));
            // try {
            // combinedTask.get();

            // } catch (InterruptedException | ExecutionException e) {
            // e.printStackTrace();
            // }
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        } finally {
            executorService.shutdown();
            executorService.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);

        }

    }

    private List<CompletableFuture<Void>> callCodeTransformation(ExecutorService threadPool, LLMTargetSection section,
            LLMTransformer transformer,
            BasicModelParameters modelParameters, String folder, String prompt) {

        List<CompletableFuture<Void>> allTasks = new ArrayList<>();

        CompletableFuture<Void> optimizationTask = CompletableFuture.runAsync(() -> {
            try {
                optimize(section.toString(),
                        folder, prompt,
                        transformer, modelParameters);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, threadPool);

        allTasks.add(optimizationTask);

        List<Loop> loops = extractLoops(section);
        for (int i = 0; i < loops.size(); i++) {
            Loop loop = loops.get(i);
            String loopId = "loop_" + (i + 1);
            // String loopId = LoopTools.getLoopName((Statement) loop);
            CompletableFuture<Void> loopByLoopOptimizationTask = CompletableFuture.runAsync(() -> {
                try {
                    optimize(loopId, loop.toString(),
                            folder,
                            prompt,
                            transformer, modelParameters);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }, threadPool);

            allTasks.add(loopByLoopOptimizationTask);

        }

        return allTasks;

    }

    private void createFormattedExperimentalCode() {
        LoopTools.addLoopName(program);

        List<LLMTargetSection> targetSections = getTargetSections();

        for (LLMTargetSection llmTargetSection : targetSections) {
            List<Loop> loops = extractLoops(llmTargetSection);
            for (Loop loopItem : loops) {
                ForLoop loop = (ForLoop) loopItem;
                PragmaAnnotation startAnnotation = new PragmaAnnotation(
                        "llm-loop-target=" + LoopTools.getLoopName(loop) + " start");
                // startAnnotation.setOneLiner(true);
                loop.annotateBefore(startAnnotation);

                PragmaAnnotation endAnnotation = new PragmaAnnotation(
                        "llm-loop-target=" + LoopTools.getLoopName(loop) + " stop");
                // endAnnotation.setOneLiner(true);
                loop.annotateAfter(endAnnotation);
                if (PrintTools.getVerbosity() >= 3) {
                    System.out.println(loop);
                }
            }
        }
    }

    @Override
    public void start() {
        try {
            // LoopTools.addLoopName(program);
            startOptimizations();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void executeCaRV() {
        String command = "your_command_here";
        String arg1 = "argument1";
        String arg2 = "argument2";
        // Add more arguments as needed

        try {
            // Create a process builder
            ProcessBuilder processBuilder = new ProcessBuilder(command, arg1, arg2);
            // Redirect error stream to output stream (optional)
            processBuilder.redirectErrorStream(true);

            // Start the process
            Process process = processBuilder.start();

            // Read the output (if needed)
            // BufferedReader reader = new BufferedReader(new
            // InputStreamReader(process.getInputStream()));
            // String line;
            // while ((line = reader.readLine()) != null) {
            // System.out.println(line);
            // }

            // Wait for the process to complete
            int exitCode = process.waitFor();

            // Print the exit code
            System.out.println("Exit Code: " + exitCode);

        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }
}
