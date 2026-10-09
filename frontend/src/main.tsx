import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ApiError } from "./api/client";
import App from "./App";
import { AuthGate } from "./auth/AuthGate";
import "./styles.css";

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // 권한·검증 오류(4xx)는 다시 시도해도 같으므로 재시도하지 않는다.
      retry: (count, error) => !(error instanceof ApiError && error.status >= 400 && error.status < 500) && count < 2,
      refetchOnWindowFocus: false,
    },
  },
});

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <AuthGate>
        <App />
      </AuthGate>
    </QueryClientProvider>
  </StrictMode>,
);
