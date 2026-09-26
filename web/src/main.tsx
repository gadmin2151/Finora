import { Component, StrictMode, type ReactNode } from "react";
import { createRoot } from "react-dom/client";
import { QueryClientProvider } from "@tanstack/react-query";
import App, { FatalError } from "./App";
import { queryClient } from "./api";
import "./styles.css";
import "./responsive.css";
import "./features.css";
import "./navigation.css";
import "./theme.css";
import "./reports.css";
import "./management.css";
import "./appearance.css";
import "./themeStore";
import "./language.css";
import "./wallet-chat.css";

class ErrorBoundary extends Component<
  { children: ReactNode },
  { error: unknown }
> {
  state = { error: null as unknown };
  static getDerivedStateFromError(error: unknown) {
    return { error };
  }
  render() {
    return this.state.error ? (
      <FatalError error={this.state.error} />
    ) : (
      this.props.children
    );
  }
}
createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <ErrorBoundary>
      <QueryClientProvider client={queryClient}>
        <App />
      </QueryClientProvider>
    </ErrorBoundary>
  </StrictMode>,
);
