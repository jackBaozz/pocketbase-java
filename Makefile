.PHONY: dev dev-server dev-ui test build native format format-check

PORT ?= 8090

MAVEN = mvn -gs settings.xml -s settings.xml

# 并行启动 Java 后端与 Vite 前端（前端将 /api 代理到后端）
dev:
	@cd UI && npm exec concurrently -- \
		--kill-others \
		--names server,web \
		--prefix-colors blue,magenta \
		"$(MAKE) -C .. dev-server" \
		"npm run dev"


dev-server:
	@$(MAVEN) compile \
		org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
		-Dexec.mainClass=io.github.jackbaozz.pocketbase.server.PocketBaseServer \
		-Dexec.args="--port $(PORT)"


dev-ui:
	@cd UI && npm run dev


test:
	@$(MAVEN) test


# 构建 Admin UI（产物直接写入 src/main/resources/pocketbase-admin/）并打包后端
build:
	@cd UI && npm run build
	@$(MAVEN) clean package


format-apply:
	@$(MAVEN) spotless:apply


format-check:
	@$(MAVEN) spotless:check


# GraalVM Native Image 编译（跳过单元测试）
build-native:
	@sh/build-native.sh
