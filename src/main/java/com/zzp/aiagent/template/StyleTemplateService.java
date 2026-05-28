package com.zzp.aiagent.template;

import com.zzp.aiagent.template.model.StyleTemplate;

import java.util.List;
import java.util.Optional;

public interface StyleTemplateService {

    List<StyleTemplate> listAll();

    Optional<StyleTemplate> getByCode(String code);

    Optional<StyleTemplate> match(String userInput);
}
