declare module 'vue-markdown-shiki' {
  import type { Component, Plugin } from 'vue';

  export const VueMarkdownIt: Component;
  export const VueMarkDownHeader: Component;
  export const VueMarkdownItProvider: Component;

  const plugin: Plugin;
  export default plugin;
}

declare module 'vue-markdown-shiki/style';
