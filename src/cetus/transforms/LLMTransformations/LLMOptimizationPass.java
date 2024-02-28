package cetus.transforms.LLMTransformations;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONObject;

import cetus.hir.Annotatable;
import cetus.hir.Annotation;
import cetus.hir.AnnotationStatement;
import cetus.hir.DFIterator;
import cetus.hir.IRTools;
import cetus.hir.PragmaAnnotation;
import cetus.hir.Program;
import cetus.hir.Traversable;
import cetus.transforms.TransformPass;

public class LLMOptimizationPass extends TransformPass {

    public static final String PASS_NAME = "LLM-Optimizations";
    public static final String PASS_CMD_OPTION = "llm-opt";
    public static final String DESCRIPTION = "experimental feature to update code sections by using LLMs";

    private String prompt;
    private HttpClient client;

    public LLMOptimizationPass(Program program) {
        super(program);
    }

    @Override
    public String getPassName() {
        return "LLM-Optimizations";
    }

    private void loadPrompt() throws Exception {
        // BufferedReader br = new BufferedReader(new FileReader(new File("")));
        // while (br.ready()) {
        // prompt += br.readLine();
        // }
        // br.close();
        prompt = """
                Given the program below, think of a way how to optimize its performance using OpenMP.
                #### Program:
                {{src-code}}

                #### Full optimized version:
                    """;
        prompt = """
                Given the program below, improve its performance using OpenMP.
                #### Program:
                {{src-code}}

                #### Full optimized version:
                """;
    }

    private HttpRequest createHTTPRequest(JSONObject body) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("YOUR_URL_HERE"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer YOUR_TOKEN_HERE")
                .POST(BodyPublishers.ofString(body.toString()))
                .build();
        return request;
    }

    protected void optimize(Traversable programSection) throws Exception {
        loadPrompt();

        client = HttpClient.newHttpClient();
        JSONObject obj = new JSONObject();
        HttpRequest request = createHTTPRequest(obj);

        HttpResponse<String> response = client.send(request, BodyHandlers.ofString());

        // Output the response status code
        System.out.println("Response status code: " + response.statusCode());
    }

    protected LLMTargetSection getLLMTarget(Traversable t) {
        return getLLMTarget(t, "experimental llm_opt start", "experimental llm_opt stop");

    }

    protected LLMTargetSection getLLMTarget(Traversable t, String startPragma, String endPragma) {
        LLMTargetSection target = new LLMTargetSection();

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
            
            if (collects) {
                target.addStatement(child);
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
                    if (values.toString().contains("experimental llm_opt start")) {
                        targetSections.add(getLLMTarget(at.getParent()));
                    }
                }

            }
        }

        return targetSections;
    }

    @Override
    public void start() {

        try {
            List<LLMTargetSection> targetSections = getTargetSections();
            // print all the target sections
            for (LLMTargetSection t : targetSections) {
                System.out.println(t);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
