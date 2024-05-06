package cetus.transforms.LLMTransformations.LLMTransformers;


import org.json.JSONArray;
import org.json.JSONObject;


public class CodeLLamaInputsOnlyTransformer implements LLMTransformer {

    public static final String MODEL = "CodeLlama-70b-Instruct-hf-Inputs-Only";
    // public static final String MODEL = "meta-llama/CodeLlama-70b-Instruct-hf";

    private String model;

    public CodeLLamaInputsOnlyTransformer() {
        this.model = MODEL;
    }

    public String getModel() {
        return model;
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

        try {

            JSONObject LLMMessage = obj;
            String content = LLMMessage.getString("inputs");

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
