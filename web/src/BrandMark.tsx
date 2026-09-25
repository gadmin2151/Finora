/** Selected origami wallet, shared with the Android launcher and loading states. */
export function BrandMark({ className = "" }: { className?: string }) {
  return (
    <span className={`origami-mark ${className}`} aria-hidden="true">
      <img src="/finora-icon.png" alt="" />
    </span>
  );
}
