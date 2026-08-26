# RELEASE.md — 发布流程

发布产物：**GitHub Packages** 上的 `com.promql:promql-core` 与
`com.promql:prometheus-api`（jar + sources + javadoc；两模块同版本发布，
依赖经父 pom `dependencyManagement` 以 `${project.version}` 对齐）。
`promql-bench` 不发布（`maven.deploy.skip`），其 shade 出的 `bench.jar`
仅本地/CI 构建产物。发布同时创建 GitHub Release 页（自动生成变更说明）。

## 触发（推送标签）

```bash
# 1. 前置：全量绿
mvn -B -ntp verify

# 2. 定版（根 + 3 个模块 pom 一次改齐；-DgenerateBackupPoms=false 免备份）
mvn -B -ntp versions:set -DnewVersion=X.Y.Z -DgenerateBackupPoms=false
#    （不引 versions 插件进 pom；或手改 4 个 pom 的 <version>/<parent><version>）

# 3. 提交并打标签（版本提交信息如 "release 0.2.0"）
git add -A && git commit -m "release X.Y.Z"
git tag vX.Y.Z
git push origin main vX.Y.Z   # 标签推送触发 .github/workflows/release.yml
```

工作流（JDK 21、按 release 17 编译）执行 `mvn -B -ntp -P release deploy`
——含全量测试，任一失败即中止，**不会产生半成品发布**；随后
`gh release create` 建 Release 页。GitHub Packages 不允许同名版本覆盖：
重发需先在仓库 Packages 里删除对应版本，删标签重推。

## 发布前检查单

- [ ] `mvn -B -ntp verify` 双 JDK（17/21）在 CI 绿（推送分支后看 Actions）。
- [ ] 4 个 pom 版本一致（`versions:set` 已保证；`grep -r "<version>" */pom.xml` 抽查）。
- [ ] README 快速上手的用例数/坐标版本与实际一致。
- [ ] 上游锚点若变（PORTING.md §0），确认差异已甄别并同步记录。

## 本地预演（不推送）

```bash
mvn -B -ntp -P release -DskipTests package   # 验证 sources/javadoc 构件可生成
ls promql-core/target/*-sources.jar promql-core/target/*-javadoc.jar \
   prometheus-api/target/*-sources.jar prometheus-api/target/*-javadoc.jar
# deploy 到本地仓库验证坐标（不碰远端；Windows 用盘符路径，POSIX 用 $PWD）
mvn -B -ntp -P release -DskipTests deploy \
    -DaltDeploymentRepository=local::default::file:///D:/workspace/CC/promql-java/target/local-repo
# 预期：promql-core / prometheus-api 的 jar+sources+javadoc 入库，
# promql-bench 日志出现 “Skipping artifact deployment”
```

## 消费方接入（GitHub Packages 为私有语义，需认证）

消费者需要具 `read:packages` 权限的 PAT（经典）或具 packages:read 的
fine-grained token：

```xml
<!-- ~/.m2/settings.xml -->
<servers>
  <server>
    <id>github</id>
    <username>你的GitHub用户名</username>
    <password>具 read:packages 的 PAT</password>
  </server>
</servers>
```

```xml
<!-- 消费项目 pom -->
<repositories>
  <repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/Oatelauser/promql-java</url>
  </repository>
</repositories>
```

依赖坐标同 README（`com.promql:promql-core` / `com.promql:prometheus-api`）。
公开仓库（Maven Central）如后续需要，另建 Sonatype Central 流程
（gpg 签名 + central-publishing 插件），当前不预设。

## 失败处置

| 症状 | 处置 |
|---|---|
| deploy 401/403 | workflow 的 `packages: write` 权限或 `server-id` 与 pom `id`（`github`）不一致 |
| deploy 409（版本已存在） | 删 Packages 内该版本 + 删标签，重推 |
| javadoc 失败 | 本地 `mvn -P release package` 复现（doclint 已关，多为标签拼写/断链） |
| Release 页创建失败但包已发 | `gh release create vX.Y.Z --generate-notes` 手动补 |
