# NuaForma branding

The rounded N combines flowing teal and dark navy forms into one whole.
Both light and dark versions retain this palette; the wordmark is dark on
a light background and white on a dark charcoal background.

- `nuaforma-light.png`: standalone light-background asset.
- `nuaforma-dark.png`: standalone dark-background asset.
- `nuaforma-approved-design.png`: original approved two-panel concept, retained
  as the design reference.

The repository README uses an HTML `picture` element to select the variant
for the viewer's light or dark color scheme, with a light-image fallback.

These are raster assets derived using the built-in image generation tool.
They are not pixel-exact crops or final vector masters; minor rendering
differences can exist between the reference and standalone versions.

## Asset preparation prompts

### Light variant

Use case: precise-object-edit. Asset type: GitHub README logo asset. Input image 1 is the edit target, an approved two-panel NuaForma logo presentation. Extract ONLY the LEFT, light-background panel into a standalone square logo image. This is an extraction of an approved design, not a redesign. Keep the complete N symbol and wordmark, exact rounded geometry, proportions, scale within its square panel, spacing, colors and original background from that panel. Text verbatim: "NuaForma". No changes to lettering, no extra text, no decoration, no other panel. Preserve teal and dark navy in the N. Output only the selected square panel.

### Dark variant

Use case: precise-object-edit. Asset type: GitHub README logo asset. Input image 1 is the edit target, an approved two-panel NuaForma logo presentation. Extract ONLY the RIGHT, dark-charcoal-background panel into a standalone square logo image. This is an extraction of an approved design, not a redesign. Keep the complete N symbol and wordmark, exact rounded geometry, proportions, scale within its square panel, spacing, colors and original background from that panel. Text verbatim: "NuaForma". No changes to lettering, no extra text, no decoration, no other panel. Preserve teal and dark navy in the N. Output only the selected square panel.
