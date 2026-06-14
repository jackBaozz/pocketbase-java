# Contributing

## 开发流程

1. 创建分支。
2. 修改源码与文档。
3. 补齐或更新测试。
4. 运行验证命令。
5. 提交 PR。

```bash
mvn -gs settings.xml -s settings.xml clean test
```

## 代码要求

- 最低兼容 JDK 17。
- 公共 API 变更必须同步更新 `README.md` 和 `docs/API设计.md`。
- 远端 HTTP 行为必须有测试覆盖。
- 不引入大型运行时依赖，除非已有功能无法用 JDK 标准库和 Jackson 合理实现。

## 提交信息

建议使用简洁动词开头：

- `feat: add file upload support`
- `fix: preserve PocketBase error body`
- `docs: update auth examples`
- `test: cover query encoding`
