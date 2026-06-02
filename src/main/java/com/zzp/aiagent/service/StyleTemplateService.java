package com.zzp.aiagent.service;

import com.zzp.aiagent.domain.template.StyleTemplate;

import java.util.List;
import java.util.Optional;

public interface StyleTemplateService {

    List<StyleTemplate> listAll();

    Optional<StyleTemplate> getByCode(String code);

    Optional<StyleTemplate> match(String userInput);
}
