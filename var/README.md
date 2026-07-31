# 本地运行目录

`var/` 保存不应提交 Git 的本地数据、日志、临时文件、导入包和历史运行产物。

- `imports/`：本地导入包
- `legacy/`：淘汰方案遗留但暂不删除的数据
- `reports/`：本地生成的报告或截图
- `tmp/`：临时文件

除本说明外，`var/` 下的内容全部被 `.gitignore` 忽略。

注意：`gallery-data/` 目前仍保留在仓库根目录，因为 `LocalObjectStorageService` 的默认路径依赖它。调整代码配置前不要移动。
