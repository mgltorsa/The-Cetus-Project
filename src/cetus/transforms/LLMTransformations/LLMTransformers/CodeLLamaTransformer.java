package cetus.transforms.LLMTransformations.LLMTransformers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;

import org.json.JSONArray;
import org.json.JSONObject;

import cetus.hir.PrintTools;

public class CodeLLamaTransformer implements LLMTransformer {

    public static final String MODEL = "codellama/CodeLlama-70b-Instruct-hf";

    private HttpClient client;
    private String model;

    public CodeLLamaTransformer() {
        client = HttpClient.newHttpClient();
        this.model = MODEL;
    }

    public String getModel() {
        return model;
    }

    private HttpRequest createHTTPRequest(JSONObject body) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api-inference.huggingface.co/models/" + this.model))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + System.getenv("HF_TOKEN"))
                .POST(BodyPublishers.ofString(body.toString()))
                .build();
        return request;
    }

    public LLMResponse transform(String promptTemplate, String programSection, BasicModelParameters modelParameters)
            throws Exception {

        String prompt = promptTemplate.replace("{{src-code}}", programSection).replace("{{mask}}", "");

        String inputs = "<s>Source: system\n\n You are a source to source C code automatic paralellizing compiler <step> ";
        inputs += "Source: user\n\n " + prompt.replaceAll("\n", " ") + " <step> ";
        inputs += "Source: assistant\nDestination: user\n\n ";
        JSONObject obj = new JSONObject();

        // inputs ="<s>Source: system\n\n You are a helpful and honest code assistant
        // expert in JavaScript. Please, provide all answers to programming questions in
        // JavaScript <step> Source: user\n\n Write a function that computes the set of
        // sums of all contiguous sublists of a given list. <step> Source:
        // assistant\nDestination: user\n\n ";
        obj.put("inputs", inputs);

        JSONObject parameters = new JSONObject();
        parameters.put("temperature", modelParameters.getTemperature());
        parameters.put("top_p", modelParameters.getTopP());
        parameters.put("return_full_text", false);
        // parameters.put("top_p", 0.8f);
        int maxNewTokens = (programSection.length() / 3) + 600;
        maxNewTokens = maxNewTokens < modelParameters.getMaxNewTokens() ? maxNewTokens
                : modelParameters.getMaxNewTokens();
        maxNewTokens = modelParameters.getMaxNewTokens();
        parameters.put("max_new_tokens", maxNewTokens);
        obj.put("parameters", parameters);

        JSONObject options = new JSONObject();
        options.put("wait_for_model", true);
        options.put("use_cache", false);
        obj.put("options", options);

        HttpRequest request = createHTTPRequest(obj);

        try {
            HttpResponse<String> response = client.send(request, BodyHandlers.ofString());

            // Output the response status code
            // System.out.println(response.body());
            int status = response.statusCode();

            // Output the response status code
            // System.out.println(response.body());

            if (status != 200) {
                throw new Exception(response.body());
            }

            String body = response.body();
            if (PrintTools.getVerbosity() >= 3) {

                System.out.println("BODY");
                System.out.println(body);

            }

            JSONArray responses = new JSONArray(body);
            JSONObject LLMMessage = responses.getJSONObject(0);
            String content = LLMMessage.getString("generated_text");

            LLMResponse llmResponse = new LLMResponse(model, prompt, LLMMessage, content, modelParameters);
            return llmResponse;

        } catch (Exception e) {
            JSONObject errorObj = new JSONObject();
            errorObj.put("error", e.getMessage());

            JSONArray stackTrace = new JSONArray(e.getStackTrace());
            errorObj.put("trace", stackTrace);
            LLMResponse llmResponse = new LLMResponse(model, prompt, errorObj, e.getMessage(), modelParameters);
            return llmResponse;

        }

    }

}
