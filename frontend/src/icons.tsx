type P = { size?: number; color?: string; sw?: number };
const base = (size = 16, color = 'currentColor', sw = 2) => ({
  'aria-hidden': true as const, width: size, height: size, viewBox: '0 0 24 24', fill: 'none',
  stroke: color, strokeWidth: sw, strokeLinecap: 'round' as const, strokeLinejoin: 'round' as const,
});

export const Sparkle = ({ size = 14, color }: P) => (
  <svg {...base(size, color)}><path d="M12 3l1.8 4.7L18.5 9.5l-4.7 1.8L12 16l-1.8-4.7L5.5 9.5l4.7-1.8z" /><path d="M19 15l.8 2.2L22 18l-2.2.8L19 21l-.8-2.2L16 18l2.2-.8z" /></svg>
);
export const Warn = ({ size = 14, color }: P) => (
  <svg {...base(size, color)} style={{ flexShrink: 0 }}><path d="M10.3 3.9L1.8 18a2 2 0 001.7 3h17a2 2 0 001.7-3L13.7 3.9a2 2 0 00-3.4 0z" /><path d="M12 9v4M12 17h.01" /></svg>
);
export const Info = ({ size = 20, color }: P) => (
  <svg {...base(size, color)} style={{ flexShrink: 0 }}><circle cx="12" cy="12" r="9" /><path d="M12 8v4M12 16h.01" /></svg>
);
export const Check = ({ size = 16, color, sw = 2.4 }: P) => (
  <svg {...base(size, color, sw)}><path d="M20 6L9 17l-5-5" /></svg>
);
export const Arrow = ({ size = 20, color }: P) => (
  <svg {...base(size, color)}><path d="M5 12h14M13 6l6 6-6 6" /></svg>
);
export const Speaker = ({ size = 14, color }: P) => (
  <svg {...base(size, color)}><path d="M11 5L6 9H2v6h4l5 4V5z" /><path d="M15.5 8.5a5 5 0 010 7M19 5a10 10 0 010 14" /></svg>
);
export const Refresh = ({ size = 16, color }: P) => (
  <svg {...base(size, color)}><path d="M21 12a9 9 0 11-3-6.7L21 8" /><path d="M21 3v5h-5" /></svg>
);
export const Clock = ({ size = 16, color = '#475467' }: P) => (
  <svg {...base(size, color)}><circle cx="12" cy="12" r="9" /><path d="M12 7v5l3 2" /></svg>
);
export const Plus = ({ size = 16, color }: P) => (
  <svg {...base(size, color, 2.4)}><path d="M12 5v14M5 12h14" /></svg>
);
export const Logo = () => (
  <svg {...base(18, '#FFFFFF')}><rect x="3" y="4.5" width="18" height="16" rx="3" /><path d="M3 9.5h18M8 2.5v4M16 2.5v4" /><path d="M9 15l2 2 4-4" /></svg>
);
