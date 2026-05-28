package com.zzp.aiagent.template;

import com.zzp.aiagent.template.model.StyleTemplate;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "app.template")
public class StyleTemplateProperties {

    private List<StyleTemplate> styles = new ArrayList<>();
}
