import { Component, type ErrorInfo, type ReactNode } from "react";

interface Props {
  name: string;
  children: ReactNode;
  fallback?: ReactNode;
}

/** 패널 하나의 렌더링 오류가 앱 전체를 멈추지 않게 한다. 다시 시도하면 자식을 새로 그린다. */
export class ErrorBoundary extends Component<Props, { error: Error | null }> {
  state = { error: null as Error | null };

  static getDerivedStateFromError(error: Error) {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error(`[${this.props.name}] 화면 오류`, error, info.componentStack);
  }

  render() {
    if (!this.state.error) return this.props.children;
    return (
      this.props.fallback ?? (
        <div className="panel-error" role="alert">
          <strong>{this.props.name}을(를) 표시하지 못했습니다.</strong>
          <p className="muted small">{this.state.error.message}</p>
          <button type="button" className="btn small" onClick={() => this.setState({ error: null })}>
            다시 시도
          </button>
        </div>
      )
    );
  }
}
