// 「下载 APK」直链端点：302 到最新正式版 Release 的 universal APK。
// 资产名格式 aicode-universal-<tag>.apk 由 .github/workflows/android-release.yml 的 Rename 步骤决定，改名时这里要同步。
const REPO = 'jieapi/aicode'
const LATEST_PAGE = `https://github.com/${REPO}/releases/latest`
// 国内直连 GitHub 下载资产常常龟速，默认套一层 gh-proxy 镜像；镜像探测不通（下线/故障）时退回 GitHub 原链。
const MIRROR_PREFIX = 'https://v6.gh-proxy.org/'
const UA = 'aicode-docs-download'
// 单次外部请求上限，避免跨境慢链路把函数拖到平台超时。
const TIMEOUT_MS = 5000

function request(url, method = 'GET') {
  return fetch(url, {
    method,
    redirect: 'manual',
    headers: { 'user-agent': UA },
    signal: AbortSignal.timeout(TIMEOUT_MS),
  })
}

async function reachable(url) {
  try {
    return (await request(url, 'HEAD')).status < 400
  } catch {
    return false
  }
}

async function resolveApkUrl() {
  // 不走 GitHub API：未认证接口按 IP 限流 60 次/小时，而 Vercel 的出口 IP 是多项目共享的。
  // /releases/latest 会 302 到 /releases/tag/<tag>，从 Location 取版本号即可，且 latest 天然跳过 pre-release。
  const page = await request(LATEST_PAGE)
  const tag = page.headers.get('location')?.match(/\/releases\/tag\/([^/?#]+)$/)?.[1]
  if (!tag) return null

  const githubUrl = `https://github.com/${REPO}/releases/download/${tag}/aicode-universal-${tag}.apk`
  if (await reachable(MIRROR_PREFIX + githubUrl)) return MIRROR_PREFIX + githubUrl
  return (await reachable(githubUrl)) ? githubUrl : null
}

export default async function handler(req, res) {
  let url = null
  try {
    url = await resolveApkUrl()
  } catch {
    url = null
  }

  const cacheControl = url
    ? 'public, s-maxage=1800, stale-while-revalidate=86400'
    : 'no-store'
  res.writeHead(302, {
    Location: url || LATEST_PAGE,
    'Cache-Control': cacheControl,
  })
  res.end()
}
