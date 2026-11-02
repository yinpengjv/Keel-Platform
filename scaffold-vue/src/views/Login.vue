<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Icon from '@/components/Icon.vue'
import Button from '@/components/ui/Button.vue'
import Field from '@/components/ui/Field.vue'
import TextInput from '@/components/ui/TextInput.vue'
import Checkbox from '@/components/ui/Checkbox.vue'
import { ApiError, authApi, portalApi } from '@/api'
import NeuralNet from '@/components/NeuralNet.vue'
import { login, navGroups } from '@/composables/auth'
import { EASE, afterTwoFrames, gsap, motionSkipped, prefersReducedMotion, trackGlow } from '@/composables/motion'
import { required, validate } from '@/utils/validate'
import { handleAssetReloadError } from '@/utils/assetReload'
import { APP } from '@/data'

const router = useRouter()
const route = useRoute()

const form = reactive({ username: '', password: '', captcha: '' })
const errors = ref<Partial<Record<keyof typeof form, string>>>({})
const remember = ref(true)
const submitting = ref(false)
const formError = ref('')
const captchaCode = ref('')
const captchaUuid = ref('')
const captchaLoading = ref(false)
/** 是否展示验证码：首次登录为 false，登录失败后由后端开启窗口期才变为 true */
const captchaRequired = ref(false)
/** 登录失败锁定策略：连续失败 maxFailures 次将锁定 lockMinutes 分钟（全局配置，与具体账号无关） */
const lockPolicy = ref({ maxFailures: 0, lockMinutes: 0 })
/** 登录前就告知规则，避免用户反复试错被锁却不知原因 */
const lockHint = computed(() => {
  const { maxFailures, lockMinutes } = lockPolicy.value
  return maxFailures > 0 ? `连续失败 ${maxFailures} 次将锁定 ${lockMinutes} 分钟` : ''
})
const root = ref<HTMLElement | null>(null)
const card = ref<HTMLElement | null>(null)
const succeeded = ref(false)

/* ── 左侧仪表：实时时钟（秒针刻度逐格推进）与服务健康 ── */
const now = ref(new Date())
const health = ref<{ ok: boolean; latency: number | null }>({ ok: false, latency: null })
const clock = computed(() => now.value.toTimeString().slice(0, 8))
const dateLine = computed(() => {
  const d = now.value
  return `${d.getFullYear()}.${String(d.getMonth() + 1).padStart(2, '0')}.${String(d.getDate()).padStart(2, '0')} · ${'SUN MON TUE WED THU FRI SAT'.split(' ')[d.getDay()]}`
})
const TICKS = Array.from({ length: 60 }, (_, i) => {
  const a = (i / 60) * Math.PI * 2 - Math.PI / 2
  const major = i % 5 === 0
  return { i, x1: 50 + Math.cos(a) * (major ? 40 : 43), y1: 50 + Math.sin(a) * (major ? 40 : 43), x2: 50 + Math.cos(a) * 47, y2: 50 + Math.sin(a) * 47, major }
})
let clockTimer = 0

async function probeHealth() {
  const start = performance.now()
  try {
    const res = await portalApi.health()
    health.value = { ok: res.status === 'UP', latency: Math.round(performance.now() - start) }
  } catch {
    health.value = { ok: false, latency: null }
  }
}

/* ── 登录卡随指针轻倾（≤ 4°），离开回正；只在可悬停设备上启用 ── */
let tiltX: ((v: number) => void) | null = null
let tiltY: ((v: number) => void) | null = null
function onTilt(event: PointerEvent) {
  if (!card.value || !tiltX || !tiltY || motionSkipped()) return
  const r = card.value.getBoundingClientRect()
  const px = (event.clientX - r.left) / r.width - 0.5
  const py = (event.clientY - r.top) / r.height - 0.5
  tiltY(Math.max(-1, Math.min(1, px * 2)) * 4)
  tiltX(Math.max(-1, Math.min(1, py * 2)) * -3)
}
function resetTilt() {
  tiltX?.(0)
  tiltY?.(0)
}

/* ── 登录成功三拍：按钮收拢成圆（放）→ 对勾圆环描出（结）→ 进入外壳 ── */
const netBurst = ref(0)
async function playSuccess() {
  succeeded.value = true
  netBurst.value += 1
  await nextTick()
  const orb = card.value?.querySelector<HTMLElement>('.success-orb')
  if (!orb || motionSkipped()) return
  const ring = orb.querySelector<SVGCircleElement>('circle')
  const check = orb.querySelector<SVGPathElement>('path')
  await new Promise<void>((resolve) => {
    const tl = gsap.timeline({ onComplete: resolve })
    tl.fromTo(orb, { width: orb.parentElement!.clientWidth }, { width: 46, duration: 0.42, ease: EASE.swap })
    if (ring) tl.fromTo(ring, { strokeDashoffset: 126 }, { strokeDashoffset: 0, duration: 0.5, ease: EASE.reveal }, '-=0.08')
    if (check) tl.fromTo(check, { strokeDashoffset: 24 }, { strokeDashoffset: 0, duration: 0.32, ease: EASE.settle }, '-=0.22')
    tl.to(orb, { scale: 1.06, duration: 0.16, ease: 'power1.out' }).to(orb, { scale: 1, duration: 0.3, ease: EASE.settle })
  })
}

const CAPABILITIES = [
  { icon: 'role', title: '角色与按钮权限', desc: '菜单、按钮两级授权，前后端同一套权限码' },
  { icon: 'layers', title: '数据权限', desc: '全部 / 本部门及下级 / 本部门 / 本人' },
  { icon: 'log', title: '操作审计', desc: '登录日志与操作日志自动记录' },
  { icon: 'code', title: '代码生成', desc: '导入数据表，生成业务模块开发骨架' },
]

async function refreshCaptcha() {
  captchaLoading.value = true
  try {
    const res = await authApi.captcha()
    captchaUuid.value = res.uuid
    captchaCode.value = res.code
  } catch {
    captchaUuid.value = ''
    captchaCode.value = ''
    formError.value = '无法连接服务，请确认后端已启动'
  } finally {
    captchaLoading.value = false
  }
  form.captcha = ''
}

/**
 * 查询该账号当前是否需要验证码。
 * force=true 时即使已经显示也会换一张图（登录失败后旧验证码已被消费）。
 */
async function checkChallenge(username: string, force = false) {
  const name = username.trim()
  if (!name) return
  try {
    const res = await authApi.loginChallenge(name)
    lockPolicy.value = {
      maxFailures: res.maxFailures ?? 0,
      lockMinutes: res.lockMinutes ?? 0,
    }
    const turnedOn = res.captchaRequired && !captchaRequired.value
    captchaRequired.value = res.captchaRequired
    if (res.captchaRequired) {
      if (turnedOn || force) await refreshCaptcha()
      return
    }
    captchaCode.value = ''
    captchaUuid.value = ''
    form.captcha = ''
    delete errors.value.captcha
  } catch {
    // 查询失败不阻塞登录，保持当前状态
  }
}

function onUsernameBlur() {
  void checkChallenge(form.username)
}

/** 页面初次进入即取回锁定规则（不传账号也能拿到全局阈值），用于密码框下方提示 */
async function loadLockPolicy() {
  try {
    const res = await authApi.loginChallenge('')
    lockPolicy.value = {
      maxFailures: res.maxFailures ?? 0,
      lockMinutes: res.lockMinutes ?? 0,
    }
  } catch {
    // 取不到就不显示提示，不影响登录
  }
}

/**
 * 登录成功、但跳转被取消时的原因说明。
 *
 * 常见两种：
 * ① 账号没有任何可见菜单（角色未分配菜单）——提示用户去分配菜单；
 * ② 会话在跳转过程中失效 —— 提示重新登录。
 * 早期版本这里一律显示「登录失败，请稍后重试」，把「密码错误」和
 * 「没有菜单」混为一谈，用户会反复重试密码。
 */
function describeNavigationFailure(): string {
  if (!navGroups.value.length) {
    return '登录成功，但当前账号没有可访问的菜单，请联系管理员分配菜单权限'
  }
  return '登录状态已失效，请重新登录'
}

async function submit() {
  formError.value = ''
  errors.value = validate(form, {
    username: [required('请输入账号')],
    password: [required('请输入密码')],
    captcha: captchaRequired.value ? [required('请输入验证码')] : [],
  })
  if (Object.keys(errors.value).length) return
  submitting.value = true
  const username = form.username.trim()
  try {
    await login({
      username,
      password: form.password,
      captcha: captchaRequired.value ? form.captcha.trim() : '',
      captchaUuid: captchaRequired.value ? captchaUuid.value : '',
    }, remember.value)
    await playSuccess()
    const redirect = typeof route.query.redirect === 'string' && route.query.redirect.startsWith('/')
      ? route.query.redirect
      : '/'
    const failure = await router.replace(redirect)
    // 导航被取消时仍停在登录页：区分原因给出准确提示，避免一律显示「登录失败」
    if (failure || router.currentRoute.value.name === 'login') {
      succeeded.value = false
      formError.value = describeNavigationFailure()
      return
    }
  } catch (error) {
    succeeded.value = false
    // 静态资源版本不一致（镜像升级后浏览器仍持有旧页面）：刷新后重试登录
    if (handleAssetReloadError(error)) return
    formError.value = error instanceof ApiError ? error.message : '登录失败，请稍后重试'
    // 登录失败后后端会开启验证码窗口，同步把验证码框显示出来
    await checkChallenge(username, true)
  } finally {
    submitting.value = false
  }
}

/* ── 一次性开场：本次会话只播一次；任意点击或按键快进而不是硬切 ── */
const INTRO_KEY = 'scaffold.login-intro'
let intro: gsap.core.Timeline | null = null
const fastForward = () => intro?.timeScale(6)

function playIntro() {
  const el = root.value
  if (!el) return
  let seen = false
  try { seen = sessionStorage.getItem(INTRO_KEY) === '1' } catch { /* 忽略 */ }
  if (prefersReducedMotion() || document.hidden) {
    el.classList.add('is-entrance-ready')
    return
  }
  const q = (s: string) => el.querySelectorAll(s)
  intro = gsap.timeline({ paused: true, onComplete: () => { intro = null } })
  intro.set(['.login-brand', '.hero-line', '.cap-item', '.login-card', '.login-plate', '.login-dial'].flatMap(s => [...q(s)]), { opacity: 0, y: 16 })
  intro.set(q('.dial-tick'), { opacity: 0.15 })
  intro.add(() => el.classList.add('is-entrance-ready'))
  // 先对焦品牌 → 标题逐行落定 → 能力逐条接通 → 登录卡交付
  intro.to(q('.login-brand'), { opacity: 1, y: 0, duration: 0.7, ease: EASE.reveal }, 0)
  intro.to(q('.hero-line'), { opacity: 1, y: 0, duration: 0.9, ease: EASE.reveal, stagger: 0.08 }, 0.12)
  intro.to(q('.login-dial'), { opacity: 1, y: 0, duration: 0.9, ease: EASE.reveal }, 0.2)
  intro.to(q('.dial-tick'), { opacity: 1, duration: 0.3, ease: 'power1.out', stagger: { each: 0.008, from: 'start' } }, 0.3)
  intro.to(q('.cap-item'), { opacity: 1, y: 0, duration: 0.7, ease: EASE.reveal, stagger: { each: 0.06, from: 'start' } }, 0.36)
  intro.to(q('.login-plate'), { opacity: 1, y: 0, duration: 0.7, ease: EASE.reveal, stagger: 0.08 }, 0.5)
  intro.to(q('.login-card'), { opacity: 1, y: 0, duration: 0.8, ease: EASE.glide }, 0.22)
  intro.add(() => gsap.set(el.querySelectorAll('.login-brand, .hero-line, .cap-item, .login-plate, .login-dial'), { clearProps: 'transform' }))
  if (seen) intro.timeScale(2.4)
  try { sessionStorage.setItem(INTRO_KEY, '1') } catch { /* 忽略 */ }
  afterTwoFrames(() => intro?.play(0))
}

// 页面被切到后台时直接走完编排，避免停在隐藏帧
function onVisibility() {
  if (document.hidden && intro) intro.progress(1, false)
}

onMounted(() => {
  probeHealth()
  void loadLockPolicy()
  clockTimer = window.setInterval(() => { if (!document.hidden) now.value = new Date() }, 1000)
  if (card.value && window.matchMedia('(hover: hover)').matches) {
    tiltX = gsap.quickTo(card.value, 'rotationX', { duration: 0.6, ease: 'power3.out' })
    tiltY = gsap.quickTo(card.value, 'rotationY', { duration: 0.6, ease: 'power3.out' })
  }
  playIntro()
  window.addEventListener('pointerdown', fastForward, { once: true })
  window.addEventListener('keydown', fastForward, { once: true })
  document.addEventListener('visibilitychange', onVisibility)
})
onBeforeUnmount(() => {
  window.clearInterval(clockTimer)
  window.removeEventListener('pointerdown', fastForward)
  window.removeEventListener('keydown', fastForward)
  document.removeEventListener('visibilitychange', onVisibility)
  intro?.kill()
})
</script>

<template>
  <div ref="root" class="login">
    <!-- 左：全幅深色画布 + 浮动玻璃面板 -->
    <section class="login-canvas" aria-label="系统介绍">
      <div class="canvas-grid" aria-hidden="true" />
      <div class="canvas-glow" aria-hidden="true" />
      <NeuralNet :burst-key="netBurst" />
      <div class="login-brand">
        <span class="brand-mark"><span class="brand-dot" /></span>
        <div>
          <div class="brand-name">{{ APP.name }}</div>
          <div class="brand-sub">{{ APP.subtitle }}</div>
        </div>
      </div>

      <div class="canvas-main">
        <div class="canvas-copy">
          <h1 class="login-title">
            <span class="hero-line">统一的后台底座，</span>
            <span class="hero-line">专注业务本身。</span>
          </h1>
        </div>

        <!-- 实时时钟：秒针对应的刻度点亮，逐秒推进（导 Conduct） -->
        <div class="login-dial" aria-hidden="true">
          <svg viewBox="0 0 100 100">
            <line
              v-for="t in TICKS"
              :key="t.i"
              :class="['dial-tick', { 'is-major': t.major, 'is-on': t.i <= now.getSeconds() }]"
              :x1="t.x1" :y1="t.y1" :x2="t.x2" :y2="t.y2"
            />
          </svg>
          <div class="dial-center">
            <span class="dial-time tnum">{{ clock }}</span>
            <span class="dial-date">{{ dateLine }}</span>
          </div>
        </div>
      </div>

      <div class="canvas-plates">
        <div class="login-plate is-health">
          <span class="eyebrow">服务状态</span>
          <span :class="['health-dot', { 'is-ok': health.ok }]" aria-hidden="true" />
          <b>{{ health.ok ? '运行正常' : health.latency === null ? '连接中断' : '状态异常' }}</b>
          <span class="tnum plate-sub">{{ health.latency === null ? '—' : `${health.latency} ms` }}</span>
        </div>
        <ul class="login-plate cap-list">
          <li v-for="cap in CAPABILITIES" :key="cap.title" class="cap-item">
            <span class="cap-ico"><Icon :name="cap.icon" :size="16" /></span>
            <span>
              <span class="cap-title">{{ cap.title }}</span>
              <span class="cap-desc">{{ cap.desc }}</span>
            </span>
          </li>
        </ul>
      </div>
      <div class="login-foot">
        <span>{{ APP.copyright }}</span>
        <span class="foot-meta">Keel Platform · {{ APP.version }}</span>
      </div>
    </section>

    <!-- 右：登录卡 -->
    <section class="login-side" @pointermove="onTilt" @pointerleave="resetTilt">
      <div ref="card" class="login-card m-float m-glow" role="region" aria-labelledby="login-heading" @pointermove="trackGlow">
        <span class="glow-layer" aria-hidden="true" />
        <div class="login-card-inner">
          <span class="eyebrow">账号登录</span>
          <h2 id="login-heading" class="login-card-title">登录</h2>
          <p class="login-card-sub">使用管理员分配的账号登录系统</p>

          <form class="login-form" novalidate @submit.prevent="submit">
            <Field label="账号" :error="errors.username">
              <TextInput v-model="form.username" icon="user" placeholder="请输入账号" autocomplete="username" :invalid="!!errors.username" @blur="onUsernameBlur" />
            </Field>
            <Field label="密码" :error="errors.password" :hint="lockHint">
              <TextInput v-model="form.password" type="password" icon="lock" placeholder="请输入密码" autocomplete="current-password" revealable :invalid="!!errors.password" />
            </Field>
            <Field
              v-if="captchaRequired"
              label="验证码"
              required
              :error="errors.captcha"
              hint="上次登录未通过，请完成验证码校验"
            >
              <div class="captcha-row">
                <TextInput v-model="form.captcha" icon="shield" placeholder="请输入右侧字符" :maxlength="6" autocomplete="off" :invalid="!!errors.captcha" />
                <button type="button" class="captcha-box m-groove" :disabled="captchaLoading" aria-label="看不清，换一张" title="看不清，换一张" @click="refreshCaptcha">
                  <span v-if="captchaCode" class="captcha-code" aria-hidden="true">
                    <span v-for="(ch, i) in captchaCode.split('')" :key="i" :style="{ rotate: `${(i % 2 ? 1 : -1) * (6 + i * 3)}deg` }">{{ ch }}</span>
                  </span>
                  <Icon v-else name="refresh" :size="16" />
                </button>
              </div>
            </Field>
            <div class="login-options">
              <Checkbox v-model="remember" label="记住登录状态" />
            </div>
            <p v-if="formError" class="login-error" role="alert"><Icon name="alert" :size="15" />{{ formError }}</p>
            <div class="login-submit-slot">
              <Button v-if="!succeeded" type="submit" variant="primary" block :loading="submitting" class="login-submit">登录</Button>
              <div v-else class="success-orb" role="status" aria-label="登录成功，正在进入">
                <svg width="30" height="30" viewBox="0 0 46 46" aria-hidden="true">
                  <circle cx="23" cy="23" r="20" stroke-dasharray="126" />
                  <path d="m15 23.5 5.5 5.5L31.5 18" stroke-dasharray="24" />
                </svg>
              </div>
            </div>
          </form>
        </div>
      </div>
    </section>
  </div>
</template>
