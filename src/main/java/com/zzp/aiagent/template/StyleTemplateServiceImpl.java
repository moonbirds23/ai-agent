package com.zzp.aiagent.template;

import com.zzp.aiagent.template.model.StyleTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@Profile("!test")
public class StyleTemplateServiceImpl implements StyleTemplateService {

    private final List<StyleTemplate> templates;

    public StyleTemplateServiceImpl(StyleTemplateProperties properties) {
        this.templates = properties.getStyles();
    }

    @Override
    public List<StyleTemplate> listAll() {
        return templates;
    }

    @Override
    public Optional<StyleTemplate> getByCode(String code) {
        return templates.stream()
                .filter(t -> t.code().equals(code))
                .findFirst();
    }

    @Override
    public Optional<StyleTemplate> match(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return Optional.empty();
        }
        String[] words = userInput.split("[\\s，,。！？!?]+");

        StyleTemplate bestMatch = null;
        int bestScore = 0;

        for (StyleTemplate template : templates) {
            int score = 0;
            List<String> keywords = template.keywords();
            if (keywords == null || keywords.isEmpty()) {
                continue;
            }
            for (String word : words) {
                if (word.isBlank()) {
                    continue;
                }
                for (String keyword : keywords) {
                    if (keyword.contains(word) || word.contains(keyword)) {
                        score++;
                    }
                }
            }
            if (score > bestScore) {
                bestScore = score;
                bestMatch = template;
            }
        }

        return Optional.ofNullable(bestMatch);
    }
}
