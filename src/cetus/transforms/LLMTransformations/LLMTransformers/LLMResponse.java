package cetus.transforms.LLMTransformations.LLMTransformers;

import org.json.JSONObject;
import org.json.JSONString;

/**
 * LLMResponse
 */
public class LLMResponse implements JSONString {

    private String model;
    private String prompt;
    private JSONObject response;
    private String content;
    private BasicModelParameters modelParameters;

    public LLMResponse(String model, String prompt, JSONObject response, String content,
            BasicModelParameters modelParameters) {
        this.model = model;
        this.prompt = prompt;
        this.response = response;
        this.content = content;
        this.modelParameters = modelParameters;
    }

    public JSONObject getResponse() {
        return response;
    }

    public String getContent() {
        return content;
    }

    public String getModel() {
        return model;
    }

    public String getPrompt() {
        return prompt;
    }

    public BasicModelParameters getModelParameters() {
        return modelParameters;
    }

    @Override
    public String toJSONString() {
        try {

            JSONObject jsonObject = new JSONObject();
            jsonObject.put("model", model);
            jsonObject.put("prompt", prompt);
            jsonObject.put("response", response);
            jsonObject.put("parameters", new JSONObject(modelParameters.toJSONString()));
            return jsonObject.toString();
        } catch (Exception e) {
            // TODO: handle exception
        }

        return this.toString();
    }

}