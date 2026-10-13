# NuaForma branding

The rounded N combines flowing teal and dark navy forms into one whole.
Both light and dark versions retain this palette; the wordmark is dark on
a light background and white on a dark charcoal background.

- `nuaforma-light.png`: standalone light-background asset.
- `nuaforma-dark.png`: standalone dark-background asset.
- `nuaforma-dark-transparent.png`: transparent dark-mode derivative with a white wordmark, used by the README and website; the original dark-background asset remains unchanged. Prepared with the built-in image generation tool by removing the charcoal background while retaining the teal/navy symbol and white wordmark. The complete preparation prompt is recorded in `site/assets/README.md`.
- `nuaforma-icon.png`: square N-only icon on a light background, intended for
  the GitHub organization avatar and other small-format uses.
- `nuaforma-icon-transparent.png`: transparent N-only avatar derivative, centered with generous margins for small and circular displays; the original icon remains unchanged.
- `nuaforma-approved-design.png`: original approved two-panel concept, retained
  as the design reference.

The repository README uses an HTML `picture` element to select the variant
for the viewer's light or dark color scheme, with a light-image fallback.

These are raster assets derived using the built-in image generation tool.
They are not pixel-exact crops or final vector masters; minor rendering
differences can exist between the reference and standalone versions.

## Asset preparation prompts

### Transparent organization icon

Prepared with the built-in image generation tool. Input is the approved transparent NuaForma logo edit target. Create a square organization avatar using ONLY the rounded flowing N mark. Remove the entire white NuaForma wordmark and leave only the original teal diagonal ribbon and dark navy rounded vertical shapes. Preserve its original colors and geometry, no redesign. Enlarge and center N to occupy about 76% of square canvas height and width, balanced generous margin suited to circular avatar cropping. Smooth crisp clean edges, no stray pixels, no shadow, no halo, no lettering, no outline, no badges. Truly transparent background with alpha, no gray or white rectangle and no painted checkerboard.

### Organization icon

Use case: precise-object-edit. Asset type: GitHub organization avatar/icon. Input image 1 is the approved NuaForma logo and edit target. Create a square standalone ICON using ONLY its upper rounded N symbol. Remove the NuaForma wordmark entirely. Preserve the flowing ribbon N geometry, curved joins and rounded vertical ends, original teal diagonal ribbon and dark navy upright areas. Center the symbol horizontally and vertically on a clean solid white background. Symbol occupies about 70% of canvas width and height, with balanced generous margin suitable for a circular avatar crop. Crisp flat graphic appearance, no new design elements, no text, no outline, no shadows, no badge, no circle drawn in image. Retain original teal and navy, do not brighten for dark mode.

### Light variant

Use case: precise-object-edit. Asset type: GitHub README logo asset. Input image 1 is the edit target, an approved two-panel NuaForma logo presentation. Extract ONLY the LEFT, light-background panel into a standalone square logo image. This is an extraction of an approved design, not a redesign. Keep the complete N symbol and wordmark, exact rounded geometry, proportions, scale within its square panel, spacing, colors and original background from that panel. Text verbatim: "NuaForma". No changes to lettering, no extra text, no decoration, no other panel. Preserve teal and dark navy in the N. Output only the selected square panel.

### Dark variant

Use case: precise-object-edit. Asset type: GitHub README logo asset. Input image 1 is the edit target, an approved two-panel NuaForma logo presentation. Extract ONLY the RIGHT, dark-charcoal-background panel into a standalone square logo image. This is an extraction of an approved design, not a redesign. Keep the complete N symbol and wordmark, exact rounded geometry, proportions, scale within its square panel, spacing, colors and original background from that panel. Text verbatim: "NuaForma". No changes to lettering, no extra text, no decoration, no other panel. Preserve teal and dark navy in the N. Output only the selected square panel.
