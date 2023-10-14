package cetus.openai.models;

import java.util.ArrayList;

public class GPTRequest {
    public String model = "gpt-3.5-turbo";
    public float temperature = 0.7f;
    public ArrayList<GPTMessage> messages = new ArrayList<>();
}
