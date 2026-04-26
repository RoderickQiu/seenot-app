import { defineConfig } from "astro/config";
import sitemap from "@astrojs/sitemap";

export default defineConfig({
  site: "https://roderickqiu.github.io/seenot-reborn",
  integrations: [sitemap()]
});
