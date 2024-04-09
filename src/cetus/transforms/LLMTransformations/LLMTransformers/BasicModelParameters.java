package cetus.transforms.LLMTransformations.LLMTransformers;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONString;

public class BasicModelParameters implements JSONString {

    public static final int MAX_NEW_TOKENS=2000;

    private float temperature;
    private float topP;
    private int maxNewTokens;

    public BasicModelParameters() {
        this(0.2f, MAX_NEW_TOKENS);
    }

    public BasicModelParameters(float temperature) {
        this(temperature, MAX_NEW_TOKENS);
    }

    public BasicModelParameters(float temperature, int maxNewTokens) {
        this(temperature, maxNewTokens, 0.1f);
    }

    public BasicModelParameters(float temperature, int maxNewTokens, float topP) {
        this.temperature = temperature;
        this.maxNewTokens = maxNewTokens;
        this.topP= topP;
    }

    public float getTemperature() {
        return temperature;
    }

    public int getMaxNewTokens() {
        return maxNewTokens;
    }

    @Override
    public String toJSONString() {
        JSONObject jsonObject = new JSONObject();
        try {
            jsonObject.put("temperature", temperature);
            jsonObject.put("max_new_tokens", maxNewTokens);
            return jsonObject.toString();
        } catch (JSONException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        return this.toString();
    }

    public float getTopP() {
        return topP;
    }

}
