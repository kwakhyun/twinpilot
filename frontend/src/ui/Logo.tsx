/** TwinPilot 마크: 항법 화살표를 실제(채움)와 디지털트윈(윤곽) 두 쪽으로 나눈 모양. public/favicon.svg와 같은 도형이다. */
export function Logo({ size = 20 }: { size?: number }) {
  return (
    <svg className="logo" width={size} height={size} viewBox="9.2 9.2 45.6 45.6" aria-hidden focusable="false">
      <path d="M49.4 11.2L11.2 26.5L29 31.6Z" fill="#3987e5" stroke="#3987e5" strokeWidth="2.6" strokeLinejoin="round" />
      <path d="M52.8 14.6L37.5 52.8L32.4 35Z" fill="none" stroke="#ff9a52" strokeWidth="3.2" strokeLinejoin="round" />
    </svg>
  );
}
