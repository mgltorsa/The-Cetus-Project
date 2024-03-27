package cetus.transforms.LLMTransformations.LLMTransformers;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONString;

public class BasicModelParameters implements JSONString {

    private float temperature;
    private int maxNewTokens;

    public BasicModelParameters() {
        this(0.7f, 2000);
    }

    public BasicModelParameters(float temperature) {
        this(temperature, 2000);
    }

    public BasicModelParameters(float temperature, int maxNewTokens) {
        this.temperature = temperature;
        this.maxNewTokens = maxNewTokens;
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

}
