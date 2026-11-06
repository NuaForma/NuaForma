# Word picture metadata and EMF headers (0.4.0-alpha.6 #213)

The native DOCX reader qualifies a bounded set of passive picture metadata in its request-local rendering view. Supported source bytes remain in an otherwise supported template; cleanup does not report removals for these constructs.

- `a:picLocks` under `pic:cNvPicPr`: documented editing-lock attributes with XML boolean values only. Unknown attributes, nested content and duplicates reject.
- Empty `a:noFill` under `pic:spPr`, and a qualified `a:ln` containing only empty `a:noFill`: no background/stroke is painted. Optional width/cap/compound/alignment metadata is validated; visible lines or unknown children remain unsupported.
- The canonical BLIP extension `{28A0092B-C50C-407e-A947-70E740481C1C}` with exactly one `a14:useLocalDpi` boolean. Dimensions still come from the required explicit physical extents. Unknown extension URIs/content are not broadly ignored. The original image bytes and crop rules are unchanged.

EMF header record length includes optional description buffers. The header type is determined using the description offset before reading extension fields, so a long classic UTF-16 description is not mistaken for OpenGL flags or a pixel-format descriptor. Alpha.6 validates description bounds/termination and extension dimensions; the source compatibility change below stops interpreting the unused text. Actual OpenGL flags, pixel-format descriptors, OpenGL records, EMF+, escapes, unsupported drawing records and resource overruns remain explicit failures. The existing bounded GDI renderer and 288 dpi policy remain unchanged.

Regression coverage uses public synthetic A4/Letter documents with large body/header-position logos, smaller footer logos and real notes. The combined fixture adds all qualified picture metadata and long classic EMF descriptions. Cleanup must retain the template byte-for-byte, and normal provider PDF output must match the previously supported baseline with image-comparison at zero tolerance. Malformed metadata/header cases and unknown/visible constructs remain negative tests. This does not qualify the unavailable private source or claim general EMF/OpenGL support.

In the development Playground, cleanup progress, errors and the ZIP download appear inside the cleanup block. Cleanup has a separate object-URL lifecycle from generation/diagnostic downloads; selecting another template clears the old cleanup result and late responses cannot offer the previous template's ZIP. The server operation still sends only the template and leaves remaining template instructions literal. Production has no cleanup control/route.

Primary references: [EMF header classification](https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-emf/de081cd7-351f-4cc2-830b-d03fb55e89ab), [picture editing locks](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.drawing.picturelocks?view=openxml-3.0.1), [local-DPI extension](https://learn.microsoft.com/en-us/openspecs/office_standards/ms-odrawxml/c05c287f-f63e-4fa9-8163-e3e106b4105f).

## Bounded description compatibility (0.4.0-alpha.7, #218)

MS-EMF specifies a null-terminated UTF-16LE header description. Alpha.6 rejects a description without its final zero code unit. Alpha.7 deliberately treats the declared description bytes as unused opaque metadata: a missing terminator or malformed text encoding alone no longer rejects an otherwise supported logo. This is compatibility handling, not a claim that such descriptions conform to the specification.

Neither inspection nor rendering decodes, logs, evaluates or scans this text for a terminator. The declared count remains bounded to 1,024 UTF-16 code units; nonempty ranges must start at or after byte 88 and end within the header record. Invalid counts/offsets, inconsistent absence fields and ranges extending into the next record still reject. Header classification continues to use the declared description offset, independently of its contents. Actual OpenGL flags, pixel-format descriptors, unsupported records, device dimensions and existing resource/cancellation checks remain enforced.

Supported original DOCX/EMF bytes remain unchanged; no repair/rewrite is inserted into the cleaned copy. Public synthetic regressions cover classic and extended headers, both logo sizes/positions, A4/Letter, retained story relationships and notes, unchanged cleanup bytes and zero-tolerance PDF parity. The private original remains unavailable and is not claimed verified. The development Playground needs no new control: its existing cleanup and normal PDF routes use the same reader.

Primary reference: [Microsoft EMF header description](https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-emf/80c0684b-9a0e-4bdb-8d6d-0e5a9e9673e7).
