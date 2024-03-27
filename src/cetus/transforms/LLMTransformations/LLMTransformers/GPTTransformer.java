package cetus.transforms.LLMTransformations.LLMTransformers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;

import org.json.JSONArray;
import org.json.JSONObject;

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

    public LLMResponse transform(String promptTemplate, String programSection, BasicModelParameters parameters)
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
        obj.put("temperature", parameters.getTemperature());

        HttpRequest request = createHTTPRequest(obj);

        HttpResponse<String> response = client.send(request, BodyHandlers.ofString());

        // Output the response status code
        System.out.println(response.body());
        JSONObject jsonResponse = new JSONObject(response.body());
        JSONArray choices = jsonResponse.getJSONArray("choices");
        JSONObject LLMMessage = choices.getJSONObject(0).getJSONObject("message");
        String content = LLMMessage.getString("content");

        LLMResponse llmResponse = new LLMResponse(model, prompt, jsonResponse, content, parameters);

        return llmResponse;

    }

}
