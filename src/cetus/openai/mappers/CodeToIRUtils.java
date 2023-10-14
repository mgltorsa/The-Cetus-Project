package cetus.openai.mappers;

import cetus.hir.ForLoop;
import cetus.hir.Traversable;
import cetus.openai.models.GPTMessage;

public class CodeToIRUtils {

    public static Traversable mapCode(String code) {
        return new ForLoop(null, null, null, null);
    }

    public static String extractCodeBlock(GPTMessage message) {
        int codeBeginning = message.content.indexOf("BEGIN-OF-THE-CODE") + "BEGIN-OF-THE-CODE".length();
        int codeEnd = message.content.indexOf("END-OF-THE-CODE");

        return message.content.substring(codeBeginning, codeEnd);

    }

}
