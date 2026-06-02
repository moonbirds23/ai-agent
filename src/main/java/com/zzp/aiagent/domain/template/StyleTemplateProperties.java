package com.zzp.aiagent.domain.template;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "app.template")
public class StyleTemplateProperties {

    private List<StyleTemplate> styles = new ArrayList<>();
}
