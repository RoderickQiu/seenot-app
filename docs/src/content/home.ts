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
  intentExample: string;
  steps: Step[];
  scenariosHeading: string;
  scenarios: string[];
  downloadHeading: string;
  downloadBody: string;
  boundariesAriaLabel: string;
  boundaries: string[];
  downloadCta: Link;
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
    eyebrow: "Intent-aware, not app-wide blocking",
    heroTitle: "Stay with what you opened your phone to do.",
    heroText:
      "Set a goal first. SeeNot watches the current screen and intervenes when the session starts drifting.",
    primaryCta: {
      label: "Download for Android",
      href: "https://github.com/RoderickQiu/seenot-reborn/releases"
    },
    secondaryCta: {
      label: "GitHub",
      href: "https://github.com/RoderickQiu/seenot-reborn"
    },
    intentExample: "For example: reply to one message, then leave. No feed spiral.",
    steps: [
      { step: "01", title: "Name the task", body: "Say what this session is for." },
      { step: "02", title: "Watch the page", body: "Stay on task, or get interrupted." },
      { step: "03", title: "Pull back", body: "Nudge, back out, or leave the app." }
    ],
    scenariosHeading: "You only meant to finish one thing, not get captured by the app.",
    scenarios: [
      "\"Open YouTube for one Python tutorial only.\"",
      "\"Check Instagram messages, not Reels.\"",
      "\"Look up a restaurant, then stop browsing and go book it.\""
    ],
    downloadHeading: "Install it now.",
    downloadBody: "Install the APK, grant the required permissions, and configure a vision model.",
    boundariesAriaLabel: "Setup requirements",
    boundaries: [
      "Works with multiple vision models: Qwen, GPT, Claude, Gemini, and more"
    ],
    downloadCta: {
      label: "Download now",
      href: "https://github.com/RoderickQiu/seenot-reborn/releases"
    },
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
    eyebrow: "和你手机里的「健康使用手机」不一样",
    heroTitle: "别让一次查资料，变成半小时刷手机。",
    heroText:
      "你只是想回一条微信、查一家餐厅、看完一个教程。SeeNot 会在你偏离这次目的时提醒你回来，不用粗暴封掉整个 App。",
    primaryCta: {
      label: "下载安卓版",
      href: "https://github.com/RoderickQiu/seenot-reborn/releases"
    },
    secondaryCta: {
      label: "GitHub",
      href: "https://github.com/RoderickQiu/seenot-reborn"
    },
    intentExample: "例如：回个微信就出来，不要顺手刷朋友圈。",
    steps: [
      { step: "01", title: "先定这一件", body: "先说这次要做什么。" },
      { step: "02", title: "只看当前页", body: "还在正事里，就继续。" },
      { step: "03", title: "偏了就拉回", body: "提醒、退一步，或者直接退出。" }
    ],
    scenariosHeading: "只是想做完这一件，别被 App 带跑",
    scenarios: [
      "“B 站只看 Python 教程，不被推荐带走。”",
      "“小红书搜餐厅，但只看吃什么，别滑到别的。”",
      "“回完微信就放下，不顺手点开朋友圈。”"
    ],
    downloadHeading: "现在安装",
    downloadBody: "安装 APK、开启权限，并配置视觉模型。",
    boundariesAriaLabel: "安装提示",
    boundaries: [
      "兼容多种视觉模型：Qwen，GPT，Claude，Gemini，……"
    ],
    downloadCta: {
      label: "现在下载",
      href: "https://github.com/RoderickQiu/seenot-reborn/releases"
    },
    docsCta: {
      label: "查看说明",
      href: "https://github.com/RoderickQiu/seenot-reborn/blob/main/README.zh-CN.md"
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
