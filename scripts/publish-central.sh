#!/usr/bin/env bash
#
# Facet 发布到 Maven Central 的包装脚本。
#
# 背景：本工程用 Maven 4 的 POM 4.1.0 构建模型，但 central-publishing-maven-plugin
# （实测 0.11.0 仍如此）按 Maven 3 语义把工程的 build POM（4.1.0 命名空间）当作
# <artifactId>.pom 上传，而 Central 的标准解析器只认 POM/4.0.0，于是整批 deployment
# 被 validation 拒收（"Failed to get coordinates" / url·licenses·scm 缺失）。
#
# 解法：保留 4.1.0 构建模型，但让上传的 pom 是 Maven 4 生成的、自包含的 consumer POM
#（POM/4.0.0、parent 内联、依赖带版本）。流程：
#   1) mvn deploy 生成 bundle（target/central-publishing/central-bundle.zip）。
#      注意：这一步插件会先上传一份 4.1.0 的 bundle，被 Central 拒——无害，只是多一个
#      未发布的 validated deployment；关键是本地留住了 bundle zip。
#   2) 修正 bundle：把每个模块的 consumer POM（*-consumer.pom，内容即 4.0.0 自包含）
#      覆盖为主 <artifactId>.pom，并复用它已有的 .asc / 校验和（内容相同，签名天然有效），
#      删掉独立的 consumer 文件。
#   3) 用 Central 的 REST API 直接上传修好的 bundle（publishingType=AUTOMATIC →
#      校验通过后自动发布）。
#
# 认证头由 ~/.m2/settings.xml 里 server id=central 的 username:password 重建：
#   Authorization: UserToken <base64(username:password)>
# 与 central-publishing-maven-plugin 内部构造方式一致。
#
set -u

MVN="${FACET_MVN:-/Users/tuanjie/.local/apache-maven-4.0.0-rc-6/bin/mvn}"
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
WORK=/tmp/facet-bundle-fix
FIXED=/tmp/facet-fixed-bundle.zip

cd "$PROJECT_DIR"

# 0) 必须用 Maven 4
MVN_VER="$("$MVN" -v 2>/dev/null | head -1 || true)"
if ! echo "$MVN_VER" | grep -q "Maven 4"; then
  echo "✗ 需要 Maven 4（当前：${MVN_VER:-未找到}）。请用 FACET_MVN 指定 Maven 4 路径。" >&2
  exit 1
fi

# 1) 生成 bundle（允许上传那步失败：BUILD FAILURE 但 zip 已落盘）
echo "==> 1/3 生成 bundle（mvn -Pcentral clean deploy）"
"$MVN" -B -Pcentral clean deploy -DskipTests > /tmp/facet-deploy.log 2>&1 || \
  echo "   （插件上传的 4.1.0 bundle 被 Central 拒，属预期；bundle zip 已生成）"
if [ ! -f target/central-publishing/central-bundle.zip ]; then
  echo "✗ 未生成 target/central-publishing/central-bundle.zip，发布中止" >&2
  exit 1
fi

# 2) 修正 bundle：consumer POM -> 主 POM
echo "==> 2/3 修正 bundle：consumer POM 覆盖为主 POM"
rm -rf "$WORK" "$FIXED"
mkdir -p "$WORK"
unzip -q target/central-publishing/central-bundle.zip -d "$WORK"
python3 - <<'PY'
import os, hashlib
root = '/tmp/facet-bundle-fix'
n = 0
for dp, _, fs in os.walk(root):
    for fn in list(fs):
        if fn.endswith('-consumer.pom'):
            base = fn[:-len('-consumer.pom')] + '.pom'
            src = os.path.join(dp, fn)
            dst = os.path.join(dp, base)
            data = open(src, 'rb').read()
            open(dst, 'wb').write(data)
            # consumer pom 自带合法签名/校验和，直接复用（内容相同）
            for ext in ('.asc', '.md5', '.sha1', '.sha256', '.sha512'):
                s = src + ext
                if os.path.exists(s):
                    open(dst + ext, 'wb').write(open(s, 'rb').read())
            for ext in ('', '.asc', '.md5', '.sha1', '.sha256', '.sha512'):
                c = src + ext
                if os.path.exists(c):
                    os.remove(c)
            n += 1
# 重新生成所有 pom 的校验和，确保与内容一致
for dp, _, fs in os.walk(root):
    for fn in fs:
        if fn.endswith('.pom'):
            p = os.path.join(dp, fn)
            d = open(p, 'rb').read()
            for algo, ext in [('md5', '.md5'), ('sha1', '.sha1'),
                              ('sha256', '.sha256'), ('sha512', '.sha512')]:
                open(p + ext, 'w').write(hashlib.new(algo, d).hexdigest())
print(f"交换模块数: {n}")
# 校验
bad = []
for dp, _, fs in os.walk(root):
    for fn in fs:
        if fn.endswith('.pom'):
            p = os.path.join(dp, fn)
            if '-consumer.pom' in fn:
                bad.append(f'残留consumer: {p}')
            elif 'POM/4.1.0' in open(p, encoding='utf-8', errors='ignore').read():
                bad.append(f'仍是4.1.0: {p}')
if bad:
    print('✗ 修正后仍有问题：', bad)
    raise SystemExit(1)
print('✓ 修正校验通过：无 4.1.0 pom、无 consumer 残留')
PY
( cd "$WORK" && zip -q -r -X "$FIXED" . )
echo "   修好的 bundle: $FIXED ($(du -h "$FIXED" | cut -f1))"

# 3) 用 Central REST API 上传（AUTOMATIC：校验后自动发布）
echo "==> 3/3 上传修好的 bundle 到 Central（AUTOMATIC）"
USER=$(python3 -c "import xml.etree.ElementTree as ET; r=ET.parse('$HOME/.m2/settings.xml').getroot(); ns={'m':'http://maven.apache.org/SETTINGS/1.2.0'}; print(r.find('m:servers/m:server[m:id=\"central\"]/m:username',ns).text)")
PASS=$(python3 -c "import xml.etree.ElementTree as ET; r=ET.parse('$HOME/.m2/settings.xml').getroot(); ns={'m':'http://maven.apache.org/SETTINGS/1.2.0'}; print(r.find('m:servers/m:server[m:id=\"central\"]/m:password',ns).text)")
CRED=$(python3 -c "import base64; print(base64.b64encode(f'$USER:$PASS'.encode()).decode())")
AUTH="UserToken $CRED"
RESP=$(curl -s -w $'\n%{http_code}' -X POST \
  "https://central.sonatype.com/api/v1/publisher/upload?name=Facet&publishingType=AUTOMATIC&userId=$USER&orgId=org" \
  -H "Authorization: $AUTH" \
  -F "bundle=@$FIXED;type=application/octet-stream")
HTTP_CODE=$(echo "$RESP" | tail -1)
DEP_ID=$(echo "$RESP" | head -1)
echo "   HTTP $HTTP_CODE, deploymentId: $DEP_ID"
if [ "$HTTP_CODE" != "201" ]; then
  echo "✗ 上传失败" >&2
  exit 1
fi
echo
echo "✓ 已上传。Central 正在校验并自动发布，稍后用以下命令确认状态："
echo "    curl -s 'https://central.sonatype.com/api/v1/publisher/deployments' -H 'Authorization: $AUTH' \\"
echo "      | python3 -c \"import json,sys; d=json.load(sys.stdin); print([(x['deploymentId'],x['deploymentState']) for x in d['deployments'] if x['deploymentId']=='$DEP_ID'])\""
