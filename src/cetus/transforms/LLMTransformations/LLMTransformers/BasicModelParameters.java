package cetus.transforms.LLMTransformations.LLMTransformers;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONString;

public class BasicModelParameters implements JSONString {

    public static final int MAX_NEW_TOKENS=2000;

    private double temperature;
    private double topP;
    private int maxNewTokens;

    public BasicModelParameters() {
        this(0.2, MAX_NEW_TOKENS);
    }

    public BasicModelParameters(double temperature) {
        this(temperature, MAX_NEW_TOKENS);
    }

    public BasicModelParameters(double temperature, int maxNewTokens) {
        this(temperature, maxNewTokens, 0.1f);
    }

    public BasicModelParameters(double temperature, int maxNewTokens, double topP) {
        this.temperature = temperature;
        this.maxNewTokens = maxNewTokens;
        this.topP= topP;
    }

    public double getTemperature() {
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

    public double getTopP() {
        return topP;
    }

}
