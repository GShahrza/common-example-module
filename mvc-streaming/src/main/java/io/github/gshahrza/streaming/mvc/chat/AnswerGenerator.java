package io.github.gshahrza.streaming.mvc.chat;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** Stands in for an LLM: produces an answer as a list of tokens (words with their leading space). */
@Component
public class AnswerGenerator {

    public List<String> tokens(String prompt) {
        String answer = "You asked: \"" + prompt + "\". Streaming lets the server send this answer piece by piece, "
                + "so the user starts reading after a few milliseconds instead of waiting for the whole text. "
                + "Chat assistants, log viewers and live dashboards all work like this.";
        List<String> tokens = new ArrayList<>();
        String[] words = answer.split(" ");
        for (int i = 0; i < words.length; i++) {
            tokens.add(i == 0 ? words[i] : " " + words[i]);
        }
        return tokens;
    }
}
