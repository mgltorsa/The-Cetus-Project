package cetus.transforms.LLMTransformations;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
import cetus.hir.Program;
import cetus.hir.Statement;
import cetus.hir.Symbol;
import cetus.hir.SymbolTools;
import cetus.hir.TranslationUnit;
import cetus.hir.Traversable;
import cetus.transforms.AnnotationParser;
import cetus.transforms.TransformPass;
import cetus.transforms.LLMTransformations.LLMTransformers.BasicModelParameters;
import cetus.transforms.LLMTransformations.LLMTransformers.CodeLLamaTransformer;
import cetus.transforms.LLMTransformations.LLMTransformers.GPTTransformer;
import cetus.transforms.LLMTransformations.LLMTransformers.LLMResponse;
import cetus.transforms.LLMTransformations.LLMTransformers.LLMTransformer;

public class LLMOptimizationPass extends TransformPass {

    public static final String PASS_NAME = "LLM-Optimizations";
    public static final String PASS_CMD_OPTION = "llm-opt";
    public static final String PASS_TEMPERATURE_CMD_OPTION = "llm-opt-temp";
    public static final String PASS_TEMPERATURE_CMD_DESCRIPTION = "Select models temperature. 0.0, 0.1...0.7";
    public static final String DESCRIPTION = "experimental feature to update code sections by using LLMs";

    private String instructionsPrompt;
    private String cotPrompt;

    protected CommandLineOptionSet commandLineOptionSet;

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

    private void loadPrompts() throws Exception {
        // BufferedReader br = new BufferedReader(new FileReader(new File("")));
        // while (br.ready()) {
        // prompt += br.readLine();
        // }
        // br.close();
        cotPrompt = """
                Given the C program below, think of a way how to optimize its performance using OpenMP.
                #### Program:
                {{src-code}}

                #### Full optimized version:
                {{mask}}
                Summarize the ways you found in the same paragraph. Then, separately use ```c to provide the code snippets
                    """;
        instructionsPrompt = """
                Given the C program below, improve its performance using OpenMP.
                #### Program:
                {{src-code}}

                #### Full optimized version:
                {{mask}}
                Only provide the code snippets, do not provide more than that. Use ```c to provide the code snippets
                """;
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

    protected Program parseProgram(LLMTargetSection programSection, String optimizedCode) throws Exception {
        // save optized code in a tempfile using buffered writer

        File tempFile = File.createTempFile("tempfile", ".c");
        // write the optimized code to the temp file
        BufferedWriter writer = new BufferedWriter(new FileWriter(tempFile));
        // Add the variables initialization to the file
        // Get program symbol table
        // Find for every traversable in the target section for the matching variable in
        // the symbol table
        // If found, add the variable declaration to the file
        // If not found, add the variable declaration to the file

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
        writer.write(optimizedCode + "\n");
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
            System.out.println(optimizedSectionProgram);
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
        String newDirName = Driver.getOptionValue(PASS_CMD_OPTION);
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

    private void saveCode(String optimizationId, String parentFolder, LLMResponse llmResponse) {
        String optimizationDirStr = parentFolder + "/" + optimizationId;
        File optimizationDir = new File(optimizationDirStr);
        if (!optimizationDir.exists()) {
            optimizationDir.mkdirs();
        }

        File file = null;

        // Loop until a unique filename is found
        int counter = 0;
        while (true) {
            String fileName = (counter == 0) ? "code.c" : "code_" + counter + ".c";
            file = new File(optimizationDir + "/" + fileName);

            // Check if the file already exists
            if (!file.exists()) {
                try {
                    // Create the file
                    if (file.createNewFile()) {
                        System.out.println("File created successfully: " + fileName);
                        BufferedWriter bw = new BufferedWriter(new FileWriter(file));
                        String code = extractCodeSnippet(llmResponse.getContent());

                        bw.write(code);
                        bw.close();

                        String llmResponseFileName = (counter == 0) ? "response.json" : "response_" + counter + ".json";
                        File llmResponseFile = new File(optimizationDir + "/" + llmResponseFileName);
                        bw = new BufferedWriter(new FileWriter(llmResponseFile));

                        bw.write(llmResponse.toJSONString());
                        bw.close();

                        String chatFilename = (counter == 0) ? "chat.txt" : "chat_" + counter + ".chat";
                        File chatFile = new File(optimizationDir + "/" + chatFilename);
                        bw = new BufferedWriter(new FileWriter(chatFile));

                        bw.write("model" + llmResponse.getModel() + "\n");
                        bw.write("parameters: " + llmResponse.getModelParameters().toJSONString() + "\n\n");
                        bw.write(llmResponse.getPrompt() + "\n\n");

                        String content = llmResponse.getContent();
                        if (content == null || content.trim().isBlank() || content.trim().isEmpty()) {
                            content = "EMPTY. Check experiments";
                        }
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

        LLMResponse response = transformer.transform(prompt, programSection, modelParameters);

        saveCode(optimizationId, folder, response);
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

    private void startOptimizations() throws Exception {
        loadPrompts();

        Float temperature = 0.7f;
        String temperatureStr = commandLineOptionSet
                .getValue(PASS_TEMPERATURE_CMD_OPTION);
        if (temperatureStr != null && !temperatureStr.isEmpty() && !temperatureStr.isBlank()) {
            temperature = Float.parseFloat(temperatureStr);
        }

        BasicModelParameters modelParameters = new BasicModelParameters(temperature);

        List<LLMTransformer> transformers = new ArrayList<>();

        transformers.add(new GPTTransformer());
        transformers.add(new CodeLLamaTransformer());

        int numThreads = 4;
        ExecutorService executorService = Executors.newFixedThreadPool(numThreads);

        for (LLMTransformer llmTransformer : transformers) {
            callCodeTransformation(executorService, llmTransformer, modelParameters);
        }
    }

    private void callCodeTransformation(ExecutorService threadPool, LLMTransformer transformer,
            BasicModelParameters modelParameters) {

        String temperatureSuffix = "" + (int) (modelParameters.getTemperature() * 100);

        List<CompletableFuture<Void>> allTasks = new ArrayList<>();

        List<LLMTargetSection> targetSections = getTargetSections();

        for (LLMTargetSection section : targetSections) {
            CompletableFuture<Void> instructionOptimizationTask = CompletableFuture.runAsync(() -> {
                try {
                    optimize(section.toString(),
                            getInstructionFolder(transformer.getModel() + "_" + temperatureSuffix), instructionsPrompt,
                            transformer, modelParameters);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }, threadPool);

            try {
                instructionOptimizationTask.get();
            } catch (Exception e) {
                System.out.println("FULL_OPTIMIZATION_ERROR");
                e.printStackTrace();
            }
            allTasks.add(instructionOptimizationTask);

            CompletableFuture<Void> cotOptimizationTask = CompletableFuture.runAsync(() -> {
                try {
                    optimize(section.toString(),
                            getCotFolder(transformer.getModel() + "_" + temperatureSuffix), cotPrompt,
                            transformer, modelParameters);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }, threadPool);

            try {
                cotOptimizationTask.get();
            } catch (Exception e) {
                System.out.println("FULL_OPTIMIZATION_ERROR");
                e.printStackTrace();
            }
            allTasks.add(cotOptimizationTask);

            List<Loop> loops = extractLoops(section);
            for (int i = 0; i < loops.size(); i++) {
                Loop loop = loops.get(i);
                String loopId = "loop_" + (i + 1);
                CompletableFuture<Void> loopByLoopInstructionTask = CompletableFuture.runAsync(() -> {
                    try {
                        optimize(loopId, loop.toString(),
                                getInstructionFolder(transformer.getModel() + "_" + temperatureSuffix),
                                instructionsPrompt,
                                transformer, modelParameters);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }, threadPool);

                try {
                    loopByLoopInstructionTask.get();
                } catch (Exception e) {
                    System.out.println("LOOP_OPTIMIZATION_ERROR");
                    e.printStackTrace();
                }

                allTasks.add(loopByLoopInstructionTask);

                CompletableFuture<Void> loopByLoopCotTask = CompletableFuture.runAsync(() -> {
                    try {
                        optimize(loopId, loop.toString(),
                                getCotFolder(transformer.getModel() + "_" + temperatureSuffix),
                                cotPrompt,
                                transformer, modelParameters);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }, threadPool);

                try {
                    loopByLoopCotTask.get();
                } catch (Exception e) {
                    System.out.println("LOOP_OPTIMIZATION_ERROR");
                    e.printStackTrace();
                }

                allTasks.add(loopByLoopCotTask);
            }
        }

        // CompletableFuture<Void> combinedTask = CompletableFuture
        // .allOf(allTasks.toArray(new CompletableFuture[allTasks.size()]));
        // try {
        // combinedTask.get();

        // } catch (InterruptedException | ExecutionException e) {
        // // TODO Auto-generated catch block
        // e.printStackTrace();
        // } finally {
        // executorService.shutdown();
        // }
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
                System.out.println(loop);
            }
        }

    }

    @Override
    public void start() {
        try {
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
