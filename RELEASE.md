# RELEASE.md — 发布流程（Maven Central）

发布产物：**Maven Central**（central.sonatype.com，Central Portal）上的
`io.github.oatelauser:promql-java`（父 pom）、`io.github.oatelauser:promql-core`
与 `io.github.oatelauser:prometheus-api`（jar + sources + javadoc，gpg 签名；
两模块同版本发布，依赖经父 pom `dependencyManagement` 以 `${project.version}`
对齐）。`promql-bench` 不发布（父 pom release profile 的 central-publishing
`excludeArtifacts` 排除），其 shade 出的 `bench.jar` 仅本地/CI 构建产物。
发布同时创建 GitHub Release 页（自动生成变更说明）。

> Central 发布**不可撤销**：版本一经 Publish 即永久占用坐标。Publish 由人在
> Portal 上最终确认（CI 只做到 VALIDATED），保留人工闸门。

## 一次性准备（spring-plus-framework 发布时已办过则全部复用）

1. **Central 账号与命名空间**：[central.sonatype.com](https://central.sonatype.com)
   用 GitHub 账号登录；Namespaces 页确认 `io.github.oatelauser` 为 Verified
   （GitHub 登录自动创建验证；若 Pending 按页面提示建临时验证仓库）。
2. **User Token**：Account → Generate User Token，得到一对凭据（password 只
   显示一次）。本机手动 deploy 写进 `~/.m2/settings.xml` 的
   `<server><id>central</id>`（id 必须与插件 `publishingServerId` 一致）。
3. **GPG 密钥**：在 **Git Bash**（Maven 使用的 shell 环境）里生成免口令密钥，
   公钥 send-keys 到 keyserver.ubuntu.com 并回查（≥2048 位）。
4. **GitHub Secrets**（本仓库 Settings → Secrets and variables → Actions）：
   `CENTRAL_USERNAME` / `CENTRAL_PASSWORD`（Token 对）、`GPG_PRIVATE_KEY`
   （`gpg --armor --export-secret-keys <密钥ID>` 整块输出，粘贴后即删导出文件）。
   完整 SOP 见 spring-plus-framework `docs/release-with-github-actions.md`。

## 触发（推送标签）

```bash
# 1. 前置：全量绿（CI 双 JDK 17/21）
mvn -B -ntp verify

# 2. 定版（根 + 3 个模块 pom 一次改齐；-DgenerateBackupPoms=false 免备份）
mvn -B -ntp versions:set -DnewVersion=X.Y.Z -DgenerateBackupPoms=false
#    （不引 versions 插件进 pom；或手改 4 个 pom 的 <version>/<parent><version>）

# 3. 提交并打标签（版本提交信息如 "release 1.0.1"）；标签推送触发 release.yml
git add -A && git commit -m "release X.Y.Z"
git tag vX.Y.Z
git push origin main vX.Y.Z
```

**tag 名必须与 pom 版本一致**（CI 用的是 tag 指向提交里的 pom 版本）；
先改版本、提交、再打 tag，顺序不能乱。

工作流（JDK 21、按 release 17 编译）：导入 GPG 私钥 → Secrets 渲染临时
settings.xml → `mvn -B -ntp -P release deploy`——含全量测试，任一失败即中止，
**不会产生半成品上传**；随后 `gh release create` 建 Release 页。
上传成功后 deployment 停在 **VALIDATED**，去
[Central → Publishing → Deployments](https://central.sonatype.com/publishing/deployments)
人工点 **Publish**（10~30 分钟后可检索）。之后回到 main 推进下一开发周期：
`mvn versions:set -DnewVersion=X.Y.Z-SNAPSHOT` + 提交推送。

## 发布前检查单

- [ ] `mvn -B -ntp verify` 双 JDK（17/21）在 CI 绿（推送分支后看 Actions）。
- [ ] 4 个 pom 版本一致（`versions:set` 已保证；`grep -r "<version>" */pom.xml` 抽查）。
- [ ] README 快速上手的用例数/坐标版本与实际一致。
- [ ] 上游锚点若变（PORTING.md §0），确认差异已甄别并同步记录。

## 本地预演（不触碰 Central）

```bash
# sources/javadoc 构件可生成（-Dgpg.skip：本机无签名密钥时跳过签名）
mvn -B -ntp -P release -DskipTests -Dgpg.skip=true package
ls promql-core/target/*-sources.jar promql-core/target/*-javadoc.jar \
   prometheus-api/target/*-sources.jar prometheus-api/target/*-javadoc.jar
```

带密钥的完整链路预演只能真上传（Portal 里对 VALIDATED 的 deployment 点
Drop 清理，不点 Publish 即不占坐标）：本机 `~/.m2/settings.xml` 配好 central
server 后 `mvn -B -ntp -P release deploy`。

## 消费方接入（Central 为公共仓库，免认证）

```xml
<dependency>
    <groupId>io.github.oatelauser</groupId>
    <artifactId>promql-core</artifactId>
    <version>X.Y.Z</version>
</dependency>
```

Maven Central 是默认仓库，消费方零配置（mirror 了 central 的私服同样自动可用）。

## 失败处置

| 症状 | 处置 |
|---|---|
| workflow 红（测试/构建/签名失败） | Actions 看日志；修复后对失败 run 点 Re-run jobs（同一 tag 引用，无需重打 tag） |
| Secrets 配置晚于 tag 推送（首跑必红） | 同上，配好后 Re-run |
| 上传被 Central 校验拒绝（FAILED） | 日志给出组件级原因（缺 sources/javadoc、签名无效、坐标非法）；修 pom 后删 tag 重推：`git tag -f vX.Y.Z && git push origin vX.Y.Z --force` |
| deployment 停在 VALIDATED 想废弃 | Portal 里点 Drop；**已 Publish 的不可撤**（只能发新版本迭代） |
| javadoc 失败 | 本地 `mvn -P release -Dgpg.skip=true package` 复现（doclint 已关，多为标签拼写/断链） |
| Release 页创建失败但包已上传 | `gh release create vX.Y.Z --generate-notes` 手动补 |

### 已知坑位（central-publishing 插件）

- `maven.deploy.skip=true` 对 central-publishing **无效**——模块会混入 bundle，
  缺 sources/javadoc 时**整个 deployment 被 Central 拒收**。排除模块必须用插件
  的 `excludeArtifacts`，且**按 artifactId 匹配**（写 GAV 全坐标不生效）。
- `skipPublishing=true` 会静默跳过整个上传（bundle 打包上传发生在 reactor
  最后一个模块的执行里），构建绿但什么都没传——不要用它排除模块。
- bundle 内每个组件都必须带 sources 与 javadoc jar，故排除非正式构件是硬需求。
