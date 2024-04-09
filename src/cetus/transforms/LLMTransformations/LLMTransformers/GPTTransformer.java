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

public class GPTTransformer implements LLMTransformer {

    public static final String MODEL = "gpt-4";

    private HttpClient client;
    private String model;

    public GPTTransformer() {
        client = HttpClient.newHttpClient();
        this.model = MODEL;
    }

    public String getModel() {
        return model;
    }

    private HttpRequest createHTTPRequest(JSONObject body) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + System.getenv("OPENAI_API"))
                .POST(BodyPublishers.ofString(body.toString()))
                .build();
        return request;
    }

    public LLMResponse transform(String promptTemplate, String programSection, BasicModelParameters modelParameters)
            throws Exception {

        JSONObject systemMessage = new JSONObject();
        systemMessage.put("role", "system");
        systemMessage.put("content", "You are a source to source C code automatic paralellizing compiler");

        JSONObject promptMessage = new JSONObject();
        promptMessage.put("role", "user");
        String prompt = promptTemplate.replace("{{src-code}}", programSection.toString()).replace("{{mask}}", "");
        promptMessage.put("content", prompt);

        JSONObject obj = new JSONObject();
        obj.put("model", MODEL);
        obj.put("messages", new JSONObject[] { systemMessage, promptMessage });
        obj.put("temperature", modelParameters.getTemperature());
        obj.put("top_p", modelParameters.getTopP());

        HttpRequest request = createHTTPRequest(obj);

        try {

            HttpResponse<String> response = client.send(request, BodyHandlers.ofString());

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
            JSONObject jsonResponse = new JSONObject(body);
            JSONArray choices = jsonResponse.getJSONArray("choices");
            JSONObject LLMMessage = choices.getJSONObject(0).getJSONObject("message");
            String content = LLMMessage.getString("content");

            LLMResponse llmResponse = new LLMResponse(model, prompt, jsonResponse, content, modelParameters);

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
