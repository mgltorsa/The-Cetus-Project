package cetus.openai;

import java.net.Authenticator;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.util.JSONPObject;

import cetus.openai.mappers.CodeToIRUtils;
import cetus.openai.mappers.JsonBodyHandler;
import cetus.openai.models.GPTMessage;
import cetus.openai.models.GPTRequest;
import cetus.openai.models.GPTResponse;

/**
 * GPTActor
 */
public class GPTActor {

    private HttpClient client;
    private ObjectWriter objectWriter;

    public GPTActor() {
        client = HttpClient.newHttpClient();
        objectWriter = new ObjectMapper().writer().withDefaultPrettyPrinter();
    }

    public void test() throws Exception {
        // create a request

        GPTRequest body = new GPTRequest();
        String bodyStr = objectWriter.writeValueAsString(body);

        System.out.println("API_KEY: " + System.getenv("OPENAI_API_KEY"));
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("https://api.openai.com/v1/chat/completions"))
                .header("accept", "application/jso n ")
                .header("Authorization", "Bearer " + System.getenv("OPENAI_API_KEY"))
                .POST(HttpRequest.BodyPublishers.ofString(bodyStr))
                .build();

        GPTResponse response = client.send(request, new JsonBodyHandler<>(GPTResponse.class)).body().get();
        GPTMessage message = response.messages.get(response.messages.size() - 1);
        String codeBlock = CodeToIRUtils.extractCodeBlock(message);
        System.out.println(codeBlock);

    }
}