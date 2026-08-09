.DEFAULT_GOAL := help

PORT ?= 8090
MAVEN = mvn -gs settings.xml -s settings.xml

APP_NAME = pocketbase
BIN_DIR = bin
JAR_NAME = pocketbase-java-all.jar
JAR = target/$(JAR_NAME)

# === 模式选择 ============================================================
# make run [dev|test|production]  /  make build [dev|test|production]
# 不带 mode 时默认 dev。dev/test/production 是空 PHONY 目标，仅用于接收参数。
MODES := dev test production
MODE_ARGS := $(filter $(MODES),$(MAKECMDGOALS))
MODE := $(if $(MODE_ARGS),$(firstword $(MODE_ARGS)),dev)

.PHONY: help run build web-build serve dev-server dev-ui check format-apply format-check build-native
.PHONY: dev test production

# mode 占位目标（空操作；@: 抑制 "Nothing to be done" 提示）
dev:
	@:
test:
	@:
production:
	@:

help:
	@echo "pocketbase-java Makefile"
	@echo ""
	@echo "运行（运行已打包的 jar）："
	@echo "  make run [dev|test|production]   默认 dev；透传参数用 ARGS=\"--port 9090\""
	@echo ""
	@echo "构建："
	@echo "  make build                      等同 make build dev，并额外生成 bin/$(APP_NAME)"
	@echo "  make build [dev|test|production] 前端构建 + 后端打包 + 生成 bin/$(APP_NAME)-<mode>"
	@echo "  make web-build                  仅构建前端（cd UI && npm run build）"
	@echo ""
	@echo "开发（热重载，无需打包）："
	@echo "  make serve                      并行：mvn exec 后端 + vite 前端"
	@echo "  make dev-server                 仅 mvn exec 启动后端"
	@echo "  make dev-ui                     仅 vite 启动前端"
	@echo ""
	@echo "其他："
	@echo "  make check                      运行单元测试（mvn test）"
	@echo "  make format-apply | format-check  Spotless 格式化 / 检查"
	@echo "  make build-native               GraalVM Native Image 编译"

# 运行打包后的 jar（需先 make build）
run:
	@test -f $(JAR) || { echo "❌ $(JAR) 不存在，请先执行: make build $(MODE)"; exit 1; }
	@echo "▶ $(APP_NAME) [profile=$(MODE)]"
	@java -jar $(JAR) start --profile $(MODE) $(ARGS)

# 前端构建 + 后端打包 + 生成 bin/$(APP_NAME)-$(MODE) 启动脚本
# make build（无 mode）默认 dev，并额外把 bin/$(APP_NAME)-dev 复制为 bin/$(APP_NAME)
build: web-build
	@$(MAVEN) clean package -DskipTests
	@cp target/pocketbase-java-*-all.jar $(JAR)
	@mkdir -p $(BIN_DIR)
	@printf '%s\n' \
		'#!/usr/bin/env sh' \
		'DIR=$$(cd "$$(dirname "$$0")/.." && pwd)' \
		'cd "$$DIR"' \
		'exec java -jar target/$(JAR_NAME) start --profile $(MODE) "$$@"' \
		> $(BIN_DIR)/$(APP_NAME)-$(MODE)
	@chmod +x $(BIN_DIR)/$(APP_NAME)-$(MODE)
	@if [ -z "$(MODE_ARGS)" ]; then \
		cp $(BIN_DIR)/$(APP_NAME)-dev $(BIN_DIR)/$(APP_NAME); \
		echo "✓ $(BIN_DIR)/$(APP_NAME)  (= dev)"; \
	else \
		echo "✓ $(BIN_DIR)/$(APP_NAME)-$(MODE)"; \
	fi

web-build:
	@cd UI && npm run build

# 并行热开发：后端（mvn exec，改代码后重编译快）+ 前端（vite，/api 代理到后端）
serve:
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

# 单元测试（test 作为运行模式名被占用，单元测试改用 check）
check:
	@$(MAVEN) test

format-apply:
	@$(MAVEN) spotless:apply

format-check:
	@$(MAVEN) spotless:check

# GraalVM Native Image 编译（跳过单元测试）
build-native:
	@sh/build-native.sh
