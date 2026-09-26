import { createElement } from "react";
import Markdown, { type Components } from "react-markdown";
import remarkGfm from "remark-gfm";

/** No relative/API URLs, custom schemes, inline HTML or remotely loaded images. */
export function assistantLink(url: string): string {
  if (
    !/^(https?:|mailto:)/i.test(url) ||
    Array.from(url).some(
      (character) =>
        character.charCodeAt(0) <= 32 || character.charCodeAt(0) === 127,
    )
  )
    return "";
  try {
    const parsed = new URL(url);
    return ["http:", "https:", "mailto:"].includes(parsed.protocol) ? url : "";
  } catch {
    return "";
  }
}
const components: Components = {
  a: ({ href, children }) =>
    href
      ? createElement(
          "a",
          {
            href,
            target: "_blank",
            rel: "noopener noreferrer",
            referrerPolicy: "no-referrer",
          },
          children,
        )
      : createElement("span", null, children),
  table: ({ children }) =>
    createElement(
      "div",
      { className: "markdown-table", tabIndex: 0 },
      createElement("table", null, children),
    ),
};
const elements = [
  "p",
  "br",
  "strong",
  "em",
  "del",
  "h1",
  "h2",
  "h3",
  "h4",
  "h5",
  "h6",
  "ul",
  "ol",
  "li",
  "blockquote",
  "pre",
  "code",
  "hr",
  "a",
  "table",
  "thead",
  "tbody",
  "tr",
  "th",
  "td",
];

export function AssistantMarkdown({ text }: { text: string }) {
  return createElement(
    "div",
    { className: "assistant-markdown" },
    createElement(Markdown, {
      remarkPlugins: [remarkGfm],
      skipHtml: true,
      allowedElements: elements,
      urlTransform: assistantLink,
      components,
      children: text,
    }),
  );
}
