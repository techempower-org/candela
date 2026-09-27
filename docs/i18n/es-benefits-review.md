# Spanish benefits corpora: review sheet (#1586)

> **Status: NEEDS NATIVE-SPEAKER REVIEW. This Spanish is not verified.**
> Drafted 2026-09-27 as an AI terminology pass. Nobody who wrote it is a native
> speaker, and it did not go through TechEMPOWER's adversarial fact-check
> pipeline. Wrong benefits information can hurt people, so the Spanish ships
> **provisional**. The app keeps its "Datos de muestra / Sample data" banner, and a
> CI gate blocks promotion until a person signs off (see [Sign-off](#sign-off)).

Covers the three bundled TechEMPOWER benefits corpora in
`feature/src/main/assets/techempower/`:

| Corpus | Feature | Issue |
|---|---|---|
| `screener_corpus.json` | Offline "Do I qualify?" screener | #1517 |
| `notice_explainers.json` | Benefits letter decoder (Notice of Action explainers) | #1516 |
| `call_cards.json` | "Make the call" guided phone scripts | #1518 |

All three are still the **seed sample** (`provenance: "seed-sample"`). This pass
does **not** make them the production corpus. The production corpus (33+ verified
programs, top ~10 CA notice types, EN/ES, each with `verifiedAt`) still has to come
from TechEMPOWER and replace these files wholesale (epic #1520, invariant 3). This
sheet only covers the Spanish that ships today.

## How the flag works

- Each corpus has a `metadata.esReview` block with
  `status: "needs-native-speaker-review"` that links back to this sheet. The
  Kotlin parsers ignore unknown keys, so the app is unchanged. The existing
  `provenance != "techempower-verified"` banner keeps the whole surface marked
  as sample data in both languages.
- `scripts/check-benefits-es-review.py` runs in the **Benefits corpus ES gate**
  workflow on any PR that touches these assets. It fails when:
  - any bilingual field has no Spanish, so the screen would silently fall back to EN; or
  - a corpus is flipped to `techempower-verified` while its Spanish is still
    `needs-native-speaker-review`, or is marked reviewed with no `reviewedBy`/`reviewedDate`.
- `python3 scripts/check-benefits-es-review.py --checklist` regenerates the
  string table at the bottom of this sheet.

## What this pass changed

Only terminology, matched to official Spanish wording. The meaning still follows
the EN seed exactly. Nothing was added that the EN doesn't say.

| Corpus / path | Before | After | Why |
|---|---|---|---|
| decoder `NA 200.whatToDo` | "…para **pedir una audiencia**…" | "…para **solicitar una audiencia estatal**…" | CDSS NA 200 (SP) and NA Back 9 use *"Audiencia estatal"* / *"solicitar una audiencia"* [S2, S3] |
| decoder `SAR 7.title` | "Recordatorio de Informe Semestral (SAR 7)" | "Aviso de recordatorio del Reporte del estado de elegibilidad (SAR 7)" | Official ES form name is *"Reporte del estado de elegibilidad (SAR 7)"*; the reminder form CF 30 is headed *"AVISO DE RECORDATORIO, SAR 7"* [S4, S5] |
| decoder `SAR 7.whatItMeans` | "El SAR 7 es un formulario…" | "El SAR 7 (Reporte del estado de elegibilidad) es un formulario…" | Same [S4, S5]; people will see this name on the paper form |
| decoder `MC 355.whatItMeans` | "…sobre una renovación…" | "…sobre la renovación de sus beneficios…" | DHCS says *"beneficios de Medi-Cal"* [S7]; small clarity fix, same meaning |
| screener `nid_water_discount.summary` | "El **Distrito de Riego de Nevada** ofrece…" | "**Nevada Irrigation District (NID)** ofrece…" | A made-up Spanish name for a proper noun. TechEMPOWER's verified ES screener writes *"NID"* [S12]; the district publishes no Spanish name we could find |
| call card `liheap.captureFields[reference]` | "Número de referencia o de caso" | "Número de referencia o número del caso" | CDSS notices label the field *"Número del caso"* [S2, S5, S7] |

## Terminology, with sources

"Verified how" says what was actually checked. **Fetched** means the official page
or PDF was downloaded and the quote read from its text. **Search index only** means
the site blocked automated fetches (HTTP 403) and the quote comes from the search
engine's title or snippet for that URL. A human should open those links.

| # | EN term (as used in corpora) | Spanish we use | Official Spanish wording | Source | Verified how | Reviewer: check |
|---|---|---|---|---|---|---|
| T1 | Notice of Action | Aviso de Acción | "AVISO DE ACCIÓN" (form header) | [S2] | Fetched | Title case vs. official all-caps is fine? |
| T2 | (state) hearing | audiencia estatal; "solicitar una audiencia" | "Audiencia estatal: … puede solicitar una audiencia" | [S2], [S3] | Fetched | Natural for a reader? |
| T3 | county worker | su trabajador del condado | "Comuníquese con su trabajador"; DHCS: "trabajador de elegibilidad" | [S2], [S7] | Fetched | Keep "del condado", or use "trabajador(a) de su caso"? Gender-neutral wording? |
| T4 | SAR 7 / semi-annual report | Reporte del estado de elegibilidad (SAR 7) | "Reporte del estado de elegibilidad SAR 7"; "Reporte sobre el estado de elegibilidad (SAR 7)" | [S4], [S5] | Fetched | CDSS uses both "del" and "sobre el"; confirm choice |
| T5 | reminder (SAR 7 reminder notice) | Aviso de recordatorio | "AVISO DE RECORDATORIO, SAR 7" (form CF 30) | [S5] | Fetched | n/a |
| T6 | recertification | recertificación | "RECERTIFICACIÓN PARA LOS BENEFICIOS DE CALFRESH"; "volver a certificar" | [S6], [S3] | Fetched | n/a |
| T7 | Medi-Cal | Medi-Cal (not translated) | "Medi-Cal (Programa de Asistencia Médica de California)" | [S2] | Fetched | Spell out once? The EN does not |
| T8 | Medi-Cal request for information (MC 355) | aviso de Medi-Cal | "SOLICITUD DE INFORMACIÓN (REQUEST FOR INFORMATION) DE MEDI-CAL" | [S7] | Fetched | See content issue C2: the title should probably change in **both** languages |
| T9 | renewal (Medi-Cal) | renovación | "Formulario de renovación \| Medi-Cal" | [S8] | Search index only | Open link, confirm |
| T10 | CalFresh | CalFresh (not translated) | CDSS forms keep "CalFresh"; TechEMPOWER ES: "CalFresh (dinero para comida / SNAP)" | [S2], [S6], [S12] | Fetched | n/a |
| T11 | SNAP | SNAP (acronym only) | "Programa de Asistencia Nutricional Suplementaria (SNAP)" | [S10] | Search index only (SSA pub title). USDA's `/es/snap` URL served an English page on 2026-09-27 | Spell out? The EN does not |
| T12 | SSI | SSI (acronym only) | "Seguridad de Ingreso Suplementario (SSI)" | [S9] | Search index only (SSA blocks fetches) | Open link, confirm |
| T13 | LIHEAP | LIHEAP (acronym) + "Ayuda con la factura de energía" | "Programa de Asistencia Energética para Hogares de Bajos Ingresos (LIHEAP)" | [S11] | Fetched | Note: *Energética*, not *de Energía*. Some county packets use the latter; CSD (the state administrator) uses *Energética* |
| T14 | utilities | servicios públicos | "Asistencia para Servicios Públicos" | [S13] | Fetched | n/a |
| T15 | 211 / free local help line | Línea de ayuda local gratuita (211) | "211 California \| Ayuda gratuita para vivienda, comida y más" | [S13] | Fetched | n/a |
| T16 | income-qualified / low-income households | hogares de bajos ingresos que califican | "hogares de bajos ingresos calificados" | [S11] | Fetched | Natural? |
| T17 | apply / qualify | solicitar / calificar | CSD: "Para solicitar los servicios de LIHEAP", "calificados" | [S11] | Fetched | n/a |
| T18 | proof (of income) | comprobante de ingresos | CDSS SAR 7: "Adjunte las pruebas requeridas" | [S4] | Fetched | "comprobante" is common usage but not the CDSS word; which is clearer? |
| T19 | case number | número del caso | "Número del caso" | [S2], [S5] | Fetched | n/a |
| T20 | Nevada County | Condado de Nevada | TechEMPOWER ES: "el condado de Nevada" | [S12] | Fetched | n/a |
| T21 | electric medical equipment | equipo médico eléctrico | No official source found | none | none | **Unsourced.** Check against PG&E's Spanish Medical Baseline wording |
| T22 | battery backup / outages | batería de respaldo / apagones | TechEMPOWER ES uses "batería … de respaldo" for PG&E programs | [S12] | Fetched | See content issue C1 |
| T23 | Project GO, FREED, NID, United Way | not translated (proper nouns) | Names of organizations | n/a | n/a | n/a |

## Content issues: EN is wrong or unverified too (for the fact-check pipeline)

Researching the Spanish turned up problems in the **English** seed content. These
aren't translation questions. They block `techempower-verified` in both languages,
and this pass deliberately left them alone (no generative fixes to benefits
content).

- **C1. `freed_battery_backup` (screener + call card) may describe a program FREED
  doesn't run.** TechEMPOWER's fact-checked `/qualify` corpus (fetched 2026-09-27)
  lists FREED only for **medical equipment reuse** (wheelchairs, walkers, and so on)
  and for bus-pass referrals. Battery backup for outages shows up there as **PG&E**
  programs (Residential Storage Initiative, generator and battery rebate, with
  Medical Baseline as the entry point). The org mapping should be confirmed or
  fixed before anyone reads this card in any language.
- **C2. `MC 355` is a *Request for Information*, not a general Medi-Cal notice.**
  DHCS's own form reads "SOLICITUD DE INFORMACIÓN … Necesitamos más información de
  usted … Tenemos que recibir esta información a más tardar el … o usted puede
  perder sus beneficios de Medi-Cal" [S7]. The EN title ("Medi-Cal notice") and
  "often about renewing or a change" undersell the deadline and the risk of losing
  coverage.
- **C3. `NA 200` is the multipurpose CalWORKs cash-aid Notice of Action**, with the
  budget included. Its own text says it does **not** change Medi-Cal or CalFresh
  benefits [S2]. "Something about your benefits is changing" is too broad.
- **C4. `SAR 7` entry conflates two forms.** The *reminder* is **CF 30** ("SAR 7
  Reminder Notice"). The **SAR 7** is the report itself [S1]. A user holding a
  CF 30 won't match "SAR 7" unless the OCR picks up the SAR 7 mention. Consider
  adding a `CF 30` alias or entry.
- **C5. Hearing deadline.** NA Back 9 says you have **90 days** from the day after the
  notice date to ask for a hearing, and that asking before the change takes effect
  keeps benefits the same [S3]. The seed says "a limited number of days". That is
  safe, but less useful than the verified fact.
- **C6. NID discount amount.** The seed says "about $9.50 off". TechEMPOWER's
  verified corpus says "Flat **$9.50/month** off treated (drinking) water, one yearly
  application", program name **LIRA** [S12, citing NID's LIRA application]. The
  missing "/month" is a real ambiguity.
- **C7. 211 claims.** "Available any time, day or night" and "the public United Way
  social-services line" weren't verified. In California, local 211s are run by
  various agencies (see "Encuentre su 211" on [S13]). Confirm the 24/7 claim and the
  operator for Nevada County.

## Sign-off

A native Spanish speaker, ideally one who knows CA benefits paperwork, should:

1. Go through **every row** in the string table below. Check that the meaning
   matches the EN, the register is plain and respectful (*usted*), and the terms
   match the Terminology table. Pay extra attention to T3, T4, T11, T12, T18 and T21.
2. Open each **Search index only** source (T9, T11, T12) and confirm the quoted wording.
3. Leave content issues C1–C7 to TechEMPOWER's fact-check. They need the pipeline,
   not a translator.
4. When the Spanish is approved, set this in each corpus's `metadata.esReview`:
   ```json
   "status": "native-speaker-reviewed",
   "reviewedBy": "<name / role>",
   "reviewedDate": "YYYY-MM-DD"
   ```
   Only then can `provenance` become `techempower-verified`. The gate enforces
   this order.

## Sources

Fetched 2026-09-27 unless marked *search index only*.

- **S1** CDSS: Translated Forms and Publications, Spanish (index; lists NA 200, CF 30, SAR 7, NA Back 9, CF 37): https://www.cdss.ca.gov/inforesources/forms-brochures/translated-forms-and-publications/spanish
- **S2** CDSS NA 200 (SP) (7/21), *Aviso de Acción, multipurpose, includes budget*: https://www.cdss.ca.gov/Portals/9/TranslatedForms/Spanish/NA_200_Spanish.pdf
- **S3** CDSS NA Back 9 (SP) (5/22), *Su derecho a una audiencia*: https://www.cdss.ca.gov/Portals/9/TranslatedForms/Spanish/NA_Back_9_Spanish.pdf
- **S4** CDSS SAR 7 (SP) (12/23), *Reporte del estado de elegibilidad*: https://www.cdss.ca.gov/Portals/9/TranslatedForms/Spanish/SAR_7_Spanish.pdf
- **S5** CDSS CF 30 (SP) (2/18), *Aviso de recordatorio, SAR 7*: https://www.cdss.ca.gov/Portals/9/TranslatedForms/Spanish/CF_30_SP.pdf
- **S6** CDSS CF 37 (SP), *Recertificación para los beneficios de CalFresh*: https://www.cdss.ca.gov/Portals/9/TranslatedForms/Spanish/CF37_SP%20CORRECTED.pdf
- **S7** DHCS MC 355 (SPA) (07/18), *Solicitud de información de Medi-Cal*: https://www.dhcs.ca.gov/wp-content/uploads/2025/10/MC-355-SPA-0718.pdf
- **S8** DHCS, *Formulario de renovación \| Medi-Cal* (*search index only*): https://www.dhcs.ca.gov/es/Medi-Cal/Pages/renewal-form.aspx
- **S9** SSA, *Seguridad de Ingreso Suplementario (SSI)* (*search index only*; SSA returns 403 to automated fetches): https://www.ssa.gov/espanol/beneficios/ssi/
- **S10** SSA pub. ES-05-10105, *Datos sobre el Programa de Asistencia Nutricional Suplementaria (SNAP)* (*search index only*): https://www.ssa.gov/pubs/ES-05-10105.pdf. USDA FNS (https://www.fns.usda.gov/es/snap) returned an English page on 2026-09-27, so it could not be used.
- **S11** CA Department of Community Services & Development (CSD), *Recursos Lingüísticos* (LIHEAP in Spanish): https://www.csd.ca.gov/Pages/Language-Resources-ESP.aspx
- **S12** TechEMPOWER `/qualify`, the fact-checked web screener (EN/ES program names and values, per-claim `.gov` provenance). Client bundle `qualify-f08cdf77b47effd0.js` as served 2026-09-27: https://techempower.org/qualify. Only **names and terms** were compared. Rules were not copied (see `docs/screener-sync.md`).
- **S13** 211 California (Spanish): https://www.211ca.org/es

## String table (every Spanish string in the corpora)

Generated with `python3 scripts/check-benefits-es-review.py --checklist`. Rerun it
after edits. `sourceNote`/`source` rows are placeholder provenance text and will be
replaced along with the production corpus.

### `screener_corpus.json` — 19 strings

| ✓ | Path | EN | ES (draft) |
|---|---|---|---|
| ☐ | `questions[county].prompt` | Which county do you live in? | ¿En qué condado vive? |
| ☐ | `questions[county].options[nevada].label` | Nevada County, CA | Condado de Nevada, CA |
| ☐ | `questions[county].options[other].label` | Somewhere else | En otro lugar |
| ☐ | `questions[income_limited].prompt` | Is your household income limited — for example, do you often worry about paying bills? | ¿Tiene su hogar ingresos limitados? Por ejemplo, ¿le preocupa a menudo pagar las facturas? |
| ☐ | `questions[on_benefits].prompt` | Do you already get CalFresh (SNAP), Medi-Cal, or SSI? | ¿Ya recibe CalFresh (SNAP), Medi-Cal o SSI? |
| ☐ | `questions[has_children].prompt` | Are there children under 18 living in your home? | ¿Hay niños menores de 18 años que viven en su hogar? |
| ☐ | `questions[medical_equipment].prompt` | Does anyone in your home rely on electric medical equipment? | ¿Alguien en su hogar depende de equipo médico eléctrico? |
| ☐ | `programs[liheap].name` | Energy bill help (LIHEAP) | Ayuda con la factura de energía (LIHEAP) |
| ☐ | `programs[liheap].summary` | Help paying your gas or electric bill, offered locally through Project GO. | Ayuda para pagar su factura de gas o electricidad, ofrecida localmente por Project GO. |
| ☐ | `programs[liheap].sourceNote` | Seed placeholder — program run by Project GO (LIHEAP). Rules pending verified corpus. | Marcador de posición — programa administrado por Project GO (LIHEAP). Reglas pendientes del corpus verificado. |
| ☐ | `programs[nid_water_discount].name` | Lower water bill (NID discount) | Factura de agua más baja (descuento de NID) |
| ☐ | `programs[nid_water_discount].summary` | Nevada Irrigation District offers a reduced water rate for income-qualified households (about $9.50 off). | Nevada Irrigation District (NID) ofrece una tarifa de agua reducida para hogares de bajos ingresos que califican (unos $9.50 de descuento). |
| ☐ | `programs[nid_water_discount].sourceNote` | Seed placeholder — NID water-rate assistance. Rules pending verified corpus. | Marcador de posición — asistencia de tarifa de agua de NID. Reglas pendientes del corpus verificado. |
| ☐ | `programs[freed_battery_backup].name` | Medical battery backup (FREED) | Batería de respaldo médica (FREED) |
| ☐ | `programs[freed_battery_backup].summary` | FREED helps people who depend on powered medical equipment get a battery backup for outages. | FREED ayuda a las personas que dependen de equipo médico eléctrico a obtener una batería de respaldo para los apagones. |
| ☐ | `programs[freed_battery_backup].sourceNote` | Seed placeholder — FREED medical battery program. Rules pending verified corpus. | Marcador de posición — programa de baterías médicas de FREED. Reglas pendientes del corpus verificado. |
| ☐ | `programs[help_211].name` | Free local help line (211) | Línea de ayuda local gratuita (211) |
| ☐ | `programs[help_211].summary` | Call 211 any time for a person who can point you to housing, food, and utility help near you. | Llame al 211 en cualquier momento para hablar con una persona que puede guiarle a ayuda de vivienda, comida y servicios públicos cerca de usted. |
| ☐ | `programs[help_211].sourceNote` | 211 is the public United Way social-services line. | 211 es la línea pública de servicios sociales de United Way. |

### `notice_explainers.json` — 15 strings

| ✓ | Path | EN | ES (draft) |
|---|---|---|---|
| ☐ | `explainers[NA 200].title` | Notice of Action (NA 200) | Aviso de Acción (NA 200) |
| ☐ | `explainers[NA 200].whatItMeans` | A Notice of Action is the standard letter a county sends when something about your benefits is changing — starting, stopping, going up, or going down. | Un Aviso de Acción es la carta estándar que envía el condado cuando algo sobre sus beneficios va a cambiar: comenzar, terminar, subir o bajar. |
| ☐ | `explainers[NA 200].whyYouGotIt` | Counties send this when they act on your case — after an application, a review, a reported change, or a scheduled recertification. | El condado lo envía cuando actúa sobre su caso: después de una solicitud, una revisión, un cambio reportado o una recertificación programada. |
| ☐ | `explainers[NA 200].whatToDo` | Read the date and the amount. If something looks wrong, you usually have a limited number of days to ask for a hearing. Call your county worker or 211 to confirm the deadline before it passes. | Lea la fecha y el monto. Si algo parece incorrecto, normalmente tiene un número limitado de días para solicitar una audiencia estatal. Llame a su trabajador del condado o al 211 para confirmar la fecha límite antes de que pase. |
| ☐ | `explainers[NA 200].source` | Seed placeholder — pending TechEMPOWER verified explainer. | Marcador de posición — pendiente del explicador verificado de TechEMPOWER. |
| ☐ | `explainers[SAR 7].title` | Semi-Annual Report reminder (SAR 7) | Aviso de recordatorio del Reporte del estado de elegibilidad (SAR 7) |
| ☐ | `explainers[SAR 7].whatItMeans` | The SAR 7 is a form you fill out twice a year to keep your benefits. This reminder means one is due soon. | El SAR 7 (Reporte del estado de elegibilidad) es un formulario que completa dos veces al año para mantener sus beneficios. Este recordatorio significa que uno vence pronto. |
| ☐ | `explainers[SAR 7].whyYouGotIt` | Your county sends it on a schedule. Missing it is one of the most common reasons benefits stop. | Su condado lo envía según un calendario. No entregarlo es una de las razones más comunes por las que se detienen los beneficios. |
| ☐ | `explainers[SAR 7].whatToDo` | Return it by the due date on the form. If you have lost it, call your county worker or 211 right away — do not wait. | Devuélvalo antes de la fecha de vencimiento en el formulario. Si lo perdió, llame a su trabajador del condado o al 211 de inmediato; no espere. |
| ☐ | `explainers[SAR 7].source` | Seed placeholder — pending TechEMPOWER verified explainer. | Marcador de posición — pendiente del explicador verificado de TechEMPOWER. |
| ☐ | `explainers[MC 355].title` | Medi-Cal notice (MC 355) | Aviso de Medi-Cal (MC 355) |
| ☐ | `explainers[MC 355].whatItMeans` | This is a Medi-Cal notice about your health coverage — often about renewing or a change in your case. | Este es un aviso de Medi-Cal sobre su cobertura de salud, a menudo sobre la renovación de sus beneficios o un cambio en su caso. |
| ☐ | `explainers[MC 355].whyYouGotIt` | Medi-Cal sends notices when coverage is renewed, changed, or needs information from you. | Medi-Cal envía avisos cuando la cobertura se renueva, cambia o necesita información de usted. |
| ☐ | `explainers[MC 355].whatToDo` | Check whether it asks for anything by a date. If it does, respond before that date. Call 211 if you are unsure what it is asking for. | Verifique si pide algo antes de una fecha. Si es así, responda antes de esa fecha. Llame al 211 si no está seguro de qué le está pidiendo. |
| ☐ | `explainers[MC 355].source` | Seed placeholder — pending TechEMPOWER verified explainer. | Marcador de posición — pendiente del explicador verificado de TechEMPOWER. |

### `call_cards.json` — 47 strings

| ✓ | Path | EN | ES (draft) |
|---|---|---|---|
| ☐ | `cards[liheap].title` | Energy bill help (LIHEAP) | Ayuda con la factura de energía (LIHEAP) |
| ☐ | `cards[liheap].bestTimeToCall` | Lines are busiest midday — try calling first thing in the morning. | Las líneas están más ocupadas al mediodía; intente llamar a primera hora de la mañana. |
| ☐ | `cards[liheap].whatToSay[0]` | Hi, my name is ___. I am calling to ask about energy bill help. | Hola, me llamo ___. Llamo para preguntar sobre ayuda con la factura de energía. |
| ☐ | `cards[liheap].whatToSay[1]` | I think I might qualify. Can you tell me what I need to apply? | Creo que podría calificar. ¿Me puede decir qué necesito para solicitar? |
| ☐ | `cards[liheap].whatToSay[2]` | What is the best next step, and is there a deadline? | ¿Cuál es el mejor siguiente paso y hay una fecha límite? |
| ☐ | `cards[liheap].whatToAsk[0]` | Are funds still available? | ¿Todavía hay fondos disponibles? |
| ☐ | `cards[liheap].whatToAsk[1]` | What documents do I need to bring? | ¿Qué documentos necesito llevar? |
| ☐ | `cards[liheap].whatToAsk[2]` | Is there an appointment or a deadline? | ¿Hay una cita o una fecha límite? |
| ☐ | `cards[liheap].whatToAsk[3]` | Who should I ask for if I call back? | ¿Por quién debo preguntar si vuelvo a llamar? |
| ☐ | `cards[liheap].captureFields[funds_available].label` | Funds available? | ¿Fondos disponibles? |
| ☐ | `cards[liheap].captureFields[appointment].label` | Appointment date / time | Fecha / hora de la cita |
| ☐ | `cards[liheap].captureFields[reference].label` | Reference or case number | Número de referencia o número del caso |
| ☐ | `cards[liheap].captureFields[notes].label` | Other notes | Otras notas |
| ☐ | `cards[liheap].source` | Seed placeholder — direct line pending verified corpus; routes via 211 for now. | Marcador de posición — línea directa pendiente del corpus verificado; se conecta por el 211 por ahora. |
| ☐ | `cards[nid_water_discount].title` | Lower water bill (NID discount) | Factura de agua más baja (descuento de NID) |
| ☐ | `cards[nid_water_discount].bestTimeToCall` | Call during weekday business hours. | Llame durante el horario laboral entre semana. |
| ☐ | `cards[nid_water_discount].whatToSay[0]` | Hi, my name is ___. I am asking about the reduced water rate. | Hola, me llamo ___. Pregunto sobre la tarifa de agua reducida. |
| ☐ | `cards[nid_water_discount].whatToSay[1]` | I have a limited income. How do I apply for the discount? | Tengo ingresos limitados. ¿Cómo solicito el descuento? |
| ☐ | `cards[nid_water_discount].whatToSay[2]` | What do you need from me to get started? | ¿Qué necesita de mí para comenzar? |
| ☐ | `cards[nid_water_discount].whatToAsk[0]` | What proof of income do you need? | ¿Qué comprobante de ingresos necesita? |
| ☐ | `cards[nid_water_discount].whatToAsk[1]` | How long does approval take? | ¿Cuánto tarda la aprobación? |
| ☐ | `cards[nid_water_discount].whatToAsk[2]` | Will the discount show on my next bill? | ¿Aparecerá el descuento en mi próxima factura? |
| ☐ | `cards[nid_water_discount].captureFields[documents].label` | Documents needed | Documentos necesarios |
| ☐ | `cards[nid_water_discount].captureFields[timeline].label` | How long approval takes | Cuánto tarda la aprobación |
| ☐ | `cards[nid_water_discount].captureFields[notes].label` | Other notes | Otras notas |
| ☐ | `cards[nid_water_discount].source` | Seed placeholder — direct line pending verified corpus; routes via 211 for now. | Marcador de posición — línea directa pendiente del corpus verificado; se conecta por el 211 por ahora. |
| ☐ | `cards[freed_battery_backup].title` | Medical battery backup (FREED) | Batería de respaldo médica (FREED) |
| ☐ | `cards[freed_battery_backup].bestTimeToCall` | Call during weekday business hours; have your equipment details ready. | Llame durante el horario laboral entre semana; tenga listos los detalles de su equipo. |
| ☐ | `cards[freed_battery_backup].whatToSay[0]` | Hi, my name is ___. Someone in my home uses electric medical equipment. | Hola, me llamo ___. Alguien en mi hogar usa equipo médico eléctrico. |
| ☐ | `cards[freed_battery_backup].whatToSay[1]` | I am asking about a battery backup for power outages. | Pregunto sobre una batería de respaldo para los apagones. |
| ☐ | `cards[freed_battery_backup].whatToSay[2]` | How do I find out if we qualify? | ¿Cómo averiguo si calificamos? |
| ☐ | `cards[freed_battery_backup].whatToAsk[0]` | What equipment qualifies? | ¿Qué equipo califica? |
| ☐ | `cards[freed_battery_backup].whatToAsk[1]` | Is there a waitlist? | ¿Hay una lista de espera? |
| ☐ | `cards[freed_battery_backup].whatToAsk[2]` | What is the next step? | ¿Cuál es el siguiente paso? |
| ☐ | `cards[freed_battery_backup].captureFields[qualifies].label` | Do we qualify? | ¿Calificamos? |
| ☐ | `cards[freed_battery_backup].captureFields[waitlist].label` | Waitlist? | ¿Lista de espera? |
| ☐ | `cards[freed_battery_backup].captureFields[notes].label` | Other notes | Otras notas |
| ☐ | `cards[freed_battery_backup].source` | Seed placeholder — direct line pending verified corpus; routes via 211 for now. | Marcador de posición — línea directa pendiente del corpus verificado; se conecta por el 211 por ahora. |
| ☐ | `cards[help_211].title` | Free local help line (211) | Línea de ayuda local gratuita (211) |
| ☐ | `cards[help_211].bestTimeToCall` | Available any time, day or night. | Disponible en cualquier momento, de día o de noche. |
| ☐ | `cards[help_211].whatToSay[0]` | Hi, I am looking for help with ___ (housing, food, utilities). | Hola, busco ayuda con ___ (vivienda, comida, servicios públicos). |
| ☐ | `cards[help_211].whatToSay[1]` | I live in ___ . What is available near me? | Vivo en ___ . ¿Qué hay disponible cerca de mí? |
| ☐ | `cards[help_211].whatToAsk[0]` | What programs am I eligible for? | ¿Para qué programas soy elegible? |
| ☐ | `cards[help_211].whatToAsk[1]` | Can you connect me directly? | ¿Me puede conectar directamente? |
| ☐ | `cards[help_211].captureFields[programs].label` | Programs they mentioned | Programas que mencionaron |
| ☐ | `cards[help_211].captureFields[notes].label` | Other notes | Otras notas |
| ☐ | `cards[help_211].source` | 211 is the public United Way social-services line. | 211 es la línea pública de servicios sociales de United Way. |
