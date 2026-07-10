# Image Retrieval MCP Server

图片检索 MCP Server，为 ai-agent 项目提供图库向量检索和 Pexels 在线图片搜索能力。

## 技术栈

- Java 21 + Spring Boot 3.5.14
- Spring AI MCP Server WebMVC (spring-ai-starter-mcp-server-webmvc 1.0.0)
- PostgreSQL + pgvector（向量检索）
- Pexels API（在线图片搜索）

## 模块结构

```
com.zzp.imageretrievalmcp
├── ImageRetrievalMcpApplication.java   # 启动类
├── config/
│   └── McpServerConfig.java           # MCP Server 配置（占位）
├── contract/                           # 请求/响应 DTO（均为 Java record）
│   ├── GallerySearchRequest.java       # 图库检索请求
│   ├── GallerySearchResponse.java      # 图库检索响应
│   ├── GalleryCandidateDTO.java        # 图库候选项
│   ├── GalleryProfileDTO.java          # 图片 AI 画像
│   ├── PexelsSearchRequest.java        # Pexels 检索请求
│   ├── PexelsSearchResponse.java       # Pexels 检索响应
│   ├── PexelsPhotoDTO.java             # Pexels 图片详情
│   └── McpCommonResponse.java          # 通用响应包装
├── tool/
│   └── HealthCheckTool.java            # 健康检查工具
├── gallery/                            # 图库检索实现（待实现）
└── pexels/                             # Pexels 检索实现（待实现）
```

## 快速开始

```bash
# 编译
JAVA_HOME="D:/develop/java/JDK/jdk-21" mvn -f mcp-servers/image-retrieval-server compile

# 运行测试
JAVA_HOME="D:/develop/java/JDK/jdk-21" mvn -f mcp-servers/image-retrieval-server test
```

## MCP 端点

- SSE 连接: `http://localhost:8232/sse`
- 消息端点: `http://localhost:8232/mcp/message`
