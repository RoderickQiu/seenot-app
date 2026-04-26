export type Locale = "en" | "zh-CN";

type Link = {
  label: string;
  href: string;
};

type Step = {
  step: string;
  title: string;
  body: string;
};

type HomeCopy = {
  lang: Locale;
  ogLocale: string;
  canonical: string;
  title: string;
  description: string;
  brandLabel: string;
  brandSubtitle: string;
  navAriaLabel: string;
  navLinks: Link[];
  languageSwitchLabel: string;
  languageSwitchHref: string;
  languageSwitchText: string;
  eyebrow: string;
  heroTitle: string;
  heroText: string;
  primaryCta: Link;
  secondaryCta: Link;
  exampleIntents: string[];
  steps: Step[];
  scenariosHeading: string;
  scenarios: string[];
  downloadHeading: string;
  downloadBody: string;
  boundariesAriaLabel: string;
  boundaries: string[];
  downloadCta: Link;
  downloadSourcesAriaLabel?: string;
  downloadSources?: Link[];
  docsCta: Link;
};

export const homeCopy: Record<"en" | "zh", HomeCopy> = {
  en: {
    lang: "en",
    ogLocale: "en_US",
    canonical: "/",
    title: "SeeNot | Intent-aware screen time intervention for Android",
    description:
      "Tell SeeNot what you are trying to do, and it watches the current screen to pull you back when you drift.",
    brandLabel: "SeeNot home",
    brandSubtitle: "Intent-aware screen time intervention",
    navAriaLabel: "Site navigation",
    navLinks: [
      { label: "Flow", href: "#flow" },
      { label: "Cases", href: "#scenes" },
      { label: "Get it", href: "#download" }
    ],
    languageSwitchLabel: "Switch to Chinese",
    languageSwitchHref: "/zh/",
    languageSwitchText: "中文",
    eyebrow: "Android",
    heroTitle: "Most of the time, you never meant to keep scrolling that long.",
    heroText:
      "Those apps are designed to pull you in, keep you there, and make you forget what you came to do. SeeNot watches the screen for this session and pulls you back when you drift, instead of blocking the whole app.",
    primaryCta: {
      label: "Download for Android",
      href: "https://github.com/RoderickQiu/seenot-app/releases"
    },
    secondaryCta: {
      label: "GitHub",
      href: "https://github.com/RoderickQiu/seenot-app"
    },
    exampleIntents: [
      "\"Let me open Reddit for 15 minutes, but only my programming subreddits.\"",
      "\"I need Amazon for one replacement charger, not random shopping.\"",
      "\"Open LinkedIn to reply to that recruiter, not scroll the feed.\""
    ],
    steps: [
      { step: "01", title: "Say the task", body: "Use plain language, not settings menus." },
      { step: "02", title: "Watch the screen", body: "SeeNot judges the current page, not just the app name." },
      { step: "03", title: "Step in on drift", body: "Nudge you, back out, or end the detour." }
    ],
    scenariosHeading: "These scenes all fit.",
    scenarios: [
      "\"Open YouTube for cooking recipes, not Shorts.\"",
      "\"No more than 10 minutes on football news.\"",
      "\"Open Airbnb to confirm check-in details, not browse other places.\"",
      "And when just want to relax? just also tell SeeNot."
    ],
    downloadHeading: "Download, connect AI, start using.",
    downloadBody: "",
    boundariesAriaLabel: "Setup requirements",
    boundaries: [
      "Supports Qwen, GPT, Claude, Gemini, and other vision models' API"
    ],
    downloadCta: {
      label: "Download now",
      href: "https://github.com/RoderickQiu/seenot-app/releases"
    },
    downloadSourcesAriaLabel: "Download sources",
    downloadSources: [
      { label: "GitHub Releases", href: "https://github.com/RoderickQiu/seenot-app/releases" }
    ],
    docsCta: {
      label: "Read more",
      href: "https://github.com/RoderickQiu/seenot-app#seenot"
    }
  },
  zh: {
    lang: "zh-CN",
    ogLocale: "zh_CN",
    canonical: "/zh/",
    title: "SeeNot | 下一代屏幕注意力管理助手",
    description:
      "SeeNot 是一款 Android 注意力管理助手。先说这次要做什么，再让系统根据屏幕内容实时判断并介入。",
    brandLabel: "SeeNot 首页",
    brandSubtitle: "下一代屏幕注意力管理助手",
    navAriaLabel: "站点导航",
    navLinks: [
      { label: "机制", href: "#flow" },
      { label: "场景", href: "#scenes" },
      { label: "安装", href: "#download" }
    ],
    languageSwitchLabel: "切换到英文版",
    languageSwitchHref: "/",
    languageSwitchText: "EN",
    eyebrow: "安卓通用",
    heroTitle: "很多时候，你并不是故意想刷那么久。",
    heroText:
      "那些 App 会一步步把你诱导进去，让你越刷越久，甚至忘了本来想做什么。SeeNot 盯的是这次会话里的当前页面；你一偏，它就把你往回拉，而不是直接封整个 App。",
    primaryCta: {
      label: "下载安卓版",
      href: "https://github.com/RoderickQiu/seenot-app/releases"
    },
    secondaryCta: {
      label: "GitHub",
      href: "https://github.com/RoderickQiu/seenot-app"
    },
    exampleIntents: [
      "“上淘宝是为了买手机壳。”",
      "“回个微信就出来，不刷朋友圈和公众号。”",
      "“我可以用 B 站 15 分钟——但只看编程教程。”"
    ],
    steps: [
      { step: "01", title: "直接说出来", body: "像平时那样说，不用先学规则。" },
      { step: "02", title: "盯当前页面", body: "看的是眼前这页，不只是 App 名字。" },
      { step: "03", title: "偏了就介入", body: "提醒你、退一步，或者结束这次绕路。" }
    ],
    scenariosHeading: "这些场景都适合。",
    scenarios: [
      "“屏蔽 QQ 空间和小游戏。”",
      "“小红书查大阪住宿攻略，不刷别的。”",
      "“刷体育新闻就刷 15 分钟。”",
      "只想放松一下，什么限制都不要？也可以。"
    ],
    downloadHeading: "下载，接 AI，开始用。",
    downloadBody: "",
    boundariesAriaLabel: "安装提示",
    boundaries: [
      "兼容 Qwen、GPT、Claude、Gemini 等视觉大模型 API"
    ],
    downloadCta: {
      label: "现在下载",
      href: "https://github.com/RoderickQiu/seenot-app/releases"
    },
    downloadSourcesAriaLabel: "下载来源",
    downloadSources: [
      { label: "GitHub Releases", href: "https://github.com/RoderickQiu/seenot-app/releases" },
      { label: "蓝奏云（高速）", href: "http://b-bu.cn/b0pnkdfuh" }
    ],
    docsCta: {
      label: "查看说明",
      href: "https://github.com/RoderickQiu/seenot-app/blob/main/README.zh-CN.md"
    }
  }
};

export function getAlternateLanguages(currentPath: string) {
  return [
    { hrefLang: "en", href: currentPath === "/" ? "/" : "/" },
    { hrefLang: "zh-CN", href: "/zh/" },
    { hrefLang: "x-default", href: "/" }
  ];
}
