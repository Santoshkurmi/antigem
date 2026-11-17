# AntiGem STEM & diagram blocks (bundle render-v1)

How AntiGem's chat draws these blocks. Each one is a fenced code block with the language name shown; anything the app can't draw stays visible as code, so a mistake never hides content. Pick the simplest block that does the job.

| Block | Library (exact version) | Best for |
|---|---|---|
| `\ce{...}` in `$...$` | built-in (mhchem syntax) | reactions, formulas, ions, equilibria |
| ```` ```smiles ```` | SmilesDrawer 2.4.1 | organic structures |
| ```` ```functionplot ```` | function-plot 1.26.0 | calculus graphs |
| ```` ```jsxgraph ```` | JSXGraph 1.14.3 | geometry, conics, draggable constructions |
| ```` ```matter ```` | Matter.js 0.20.0 | mechanics simulations |
| ```` ```tikz ```` | TikZJax 1.0.0-beta24 + circuitikz 1.0, pgfplots 1.18.1, chemfig 1.4, tikz-cd | circuits, FBDs, optics, textbook figures |
| ```` ```mermaid ```` | Mermaid 10.9.8 | flowcharts, mind maps |

Files of the bundle: `{{BUNDLE_DIR}}`

---

## 1. Chemistry: `\ce{...}`

Write it inside math: `$\ce{...}$` inline or `$$\ce{...}$$` on its own line. Drawn natively (instant, works offline).

| Write | Meaning |
|---|---|
| `H2SO4`, `Ca(OH)2`, `[Cu(NH3)4]^2+` | digits after an element, `)` or `]` are subscripts |
| `2H2O`, `1/2O2`, `(1/2)O2`, `0.5 O2` | a number at the start is the coefficient |
| `nH2O`, `x Na2SO4`, `2nH2O`, `(n-1) H2O` | amounts with a variable (n, x, y, z attached; any single lower-case letter as its own word) |
| `Na+`, `OH-`, `NO3-`, `SO4^2-`, `Fe^{3+}`, `e-` | charge: trailing `+`/`-`, or after `^` (use `^` when the charge has a number: `Fe3+` means Fe₃⁺) |
| `Fe^{II}`, `Mn^{+VII}O4-` | oxidation states |
| `Cl.`, `OH.`, `OCO^{.-}` | radicals (dot at the end of a formula, or `.` in the charge) |
| `H2O(l)`, `NaCl(aq)`, `CO2(g)` | states |
| `A + B` | plus needs spaces around it |
| `->`, `<-`, `<=>`, `<->`, `<-->` | arrows |
| `<=>>`, `<<=>` | equilibrium favouring products / reactants |
| `->[above][below]` | text on the arrow (itself read as `\ce`) |
| ` ^ ` / ` v ` (as separate words) | gas evolved / precipitate |
| `CuSO4*5H2O` | addition compound dot |
| `CH3-CH3`, `CH2=CH2`, `HC#CH`, `CH3~CH3` | bonds inside a formula |
| `\bond{-}` `\bond{=}` `\bond{#}` `\bond{~}` `\bond{~-}` `\bond{...}` `\bond{->}` | mhchem bond commands |
| `^{14}_{6}C`, `^{235}U` | isotopes |
| `\Delta`, `h\nu`, `$x$` | commands and math pass through |
| `\pu{25 kJ/mol}`, `\pu{8.314 J K-1 mol-1}`, `\pu{9.8 m s^-2}`, `\pu{1.2e3 J}`, `\pu{6.0 x 10^-3 mol L-1}`, `\pu{25 °C}`, `\pu{5 μm}`, `\pu{10 kΩ}` | numbers with units (unit exponents with or without `^`) |
| `H₂O`, `SO₄²⁻`, `→`, `⇌`, `↑`, `↓` | typed Unicode is understood too |

$$\ce{C6H6 + HNO3 ->[H2SO4][55 °C] C6H5NO2 + H2O}$$

$$\ce{N2(g) + 3H2(g) <=>[Fe/Mo][450 °C] 2NH3(g)}$$

$$\ce{Ba^2+ + SO4^2- -> BaSO4 v}$$

$$\ce{2KClO3 ->[MnO2][\Delta] 2KCl + 3O2 ^}$$

$$\ce{CH3COOH <=>> CH3COO- + H+}$$

Inline works too: $\ce{CuSO4*5H2O}$, $\ce{^{14}_{6}C}$, $\ce{Cl.}$, $\Delta H = \pu{-92 kJ/mol}$, $R = \pu{8.314 J K-1 mol-1}$.

Arrow text may be words (`->[conc. H2SO4]`), `{text}` or `\text{...}`. Not supported: mhchem's rarest features (3D bond shapes, `\ce` options like `\mhchemoptions`); write plain LaTeX for those.

---

## 2. Molecules: ```` ```smiles ````

One molecule per line: the SMILES string, then (after a space) an optional caption. Several lines are drawn side by side.

```smiles
CC(=O)Oc1ccccc1C(=O)O Aspirin
C1=CC=CC=C1 Benzene
C[C@H](N)C(=O)O L-alanine
```

- Aromatic rings: lowercase atoms (`c1ccccc1`). Stereo: `@`/`@@`, `/` and `\` for E/Z.
- Charges `[NH4+]`, `[O-]`; isotopes `[13CH4]`.
- For mechanisms, chair conformations or Fischer projections use TikZ with `chemfig` (section 6) instead.

---

## 3. Calculus graphs: ```` ```functionplot ````

The body is the `functionPlot(options)` object as JSON (a JavaScript object literal also works). Don't set `target`; the app sets the width (and height when you leave it out).

```functionplot
{
  "title": "f(x) = x^3 - 3x and f'(x)",
  "xAxis": { "domain": [-3, 3] },
  "yAxis": { "domain": [-4, 4] },
  "data": [
    { "fn": "x^3 - 3x", "derivative": { "fn": "3x^2 - 3", "updateOnMouseMove": true } },
    { "fn": "3x^2 - 3", "color": "#dc2626" }
  ]
}
```

Useful options per item in `data`:
- `"fn": "sin(x)"`: expressions use `x`, `^`, `sin cos tan exp log sqrt abs`, constants `PI`, `E`
- `"closed": true` with `"range": [a, b]`: shade the area under the curve (definite integrals)
- `"fnType": "implicit", "fn": "x^2/9 + y^2/4 - 1"`: conics and other implicit curves
- `"fnType": "parametric", "x": "cos(t)", "y": "sin(t)", "range": [0, 6.28]`
- `"fnType": "polar", "r": "2*sin(4*theta)"`
- `"fnType": "points", "points": [[1,1],[2,4]], "graphType": "scatter"`
- `"fnType": "vector", "vector": [2, 1], "offset": [0, 0], "graphType": "polyline"`
- `"graphType": "polyline"` for Riemann sums with steps or functions with jumps
- `"derivative": {"fn": "...", "x0": 1}`: tangent line at x = 1
- top level: `"grid": false` to hide the grid, `"annotations": [{"x": 1, "text": "x = 1"}, {"y": 2}]`

Area under the curve:

```functionplot
{ "data": [ { "fn": "x^2", "range": [0, 2], "closed": true }, { "fn": "x^2" } ], "xAxis": { "domain": [-1, 3] } }
```

---

## 4. Geometry: ```` ```jsxgraph ````

The body is JavaScript. `board` is a ready JSXGraph board (boundingbox `[-6, 6, 6, -6]`, axes on). For another view, call `JXG.JSXGraph.initBoard('box', {...})` yourself (the app then makes no board). First line `// height: 300` sets the height in px (default: square, up to 380). Points are draggable with one finger.

```jsxgraph
// height: 320
var A = board.create('point', [-3, -2], {name: 'A'});
var B = board.create('point', [3, -2], {name: 'B'});
var C = board.create('point', [0, 3], {name: 'C'});
board.create('polygon', [A, B, C], {fillOpacity: 0.1});
var cc = board.create('circumcircle', [A, B, C], {strokeColor: '#2563eb', center: {name: 'O', visible: true}});
board.create('angle', [B, A, C], {radius: 0.8, name: 'α'});
```

Handy elements: `point`, `segment`, `line`, `circle`, `polygon`, `angle`, `perpendicular`, `parallel`, `midpoint`, `intersection`, `tangent`, `glider`, `slider`, `functiongraph`, `curve`, `conic`, `ellipse`, `hyperbola`, `parabola`, `text`, `arrow`. Sliders make interactive demos:

```jsxgraph
// height: 300
var a = board.create('slider', [[-5, 5], [2, 5], [-3, 1, 3]], {name: 'a'});
board.create('functiongraph', [function (x) { return a.Value() * x * x; }], {strokeColor: '#dc2626'});
```

---

## 5. Physics simulations: ```` ```matter ````

The body is JavaScript for Matter.js. Ready: `Matter`, `engine`, `world`, `render`, `runner`, `width`, `height` (px of the canvas). The app adds touch dragging and starts the simulation after your code, so don't call `Render.run` / `Runner.run`. First line `// height: 320` sets the height. The block's restart button runs it again.

```matter
// height: 300
const { Bodies, Body, Composite, Constraint } = Matter;
const balls = [];
for (let i = 0; i < 5; i++) {
  const x = width / 2 - 80 + i * 40;
  const ball = Bodies.circle(x, 200, 19, { restitution: 1, friction: 0, frictionAir: 0.0001, slop: 1, inertia: Infinity });
  balls.push(ball);
  Composite.add(world, Constraint.create({ pointA: { x, y: 40 }, bodyB: ball, stiffness: 1 }));
}
Composite.add(world, balls);
Body.translate(balls[0], { x: -120, y: -70 }); // pull the first ball back; the string keeps its length
```

---

## 6. Circuits, FBDs, optics, textbook figures: ```` ```tikz ````

The body is a normal TikZ picture: `\begin{tikzpicture}...\end{tikzpicture}` or `\begin{circuitikz}...\end{circuitikz}`. TeX compiles it on the phone (a few seconds the first time), and the SVG is cached so it opens instantly after that.

- Preamble lines may go at the top: `\usepackage{...}` and `\usetikzlibrary{...}` (they are moved to the right place). No `\documentclass` / `\begin{document}` needed (they are ignored).
- Packages: circuitikz 1.0, pgfplots 1.18.1, chemfig 1.4, tikz-cd, amsmath, amssymb, array, tikz-3dplot. `circuitikz`, `pgfplots` (`axis`), `chemfig` (`\chemfig`) and `tikz-cd` are added automatically when used.
- All standard TikZ libraries work (`arrows.meta`, `calc`, `patterns`, `decorations.pathmorphing`, `angles`, `quotes`, `positioning`, ...).
- Not available: system fonts (`fontspec`), `\includegraphics`, external files, siunitx (write units by hand: `$10\,\Omega$`).

RC circuit (circuitikz):

```tikz
\begin{circuitikz}[american]
\draw (0,0) to[battery1, l=$V$] (0,3)
  to[R, l=$R_1$] (3,3)
  to[C, l=$C$] (3,0) -- (0,0);
\end{circuitikz}
```

Block on an incline (FBD):

```tikz
\usetikzlibrary{patterns, arrows.meta}
\begin{tikzpicture}[>=Stealth]
\fill[pattern=north east lines] (0,0) -- (5,0) -- (5,-0.2) -- (0,-0.2) -- cycle;
\draw[thick] (0,0) -- (5,0) -- (5,2.5) -- cycle;
\begin{scope}[shift={(2.6,1.3)}, rotate=26.57]
  \draw[fill=blue!15] (-0.5,0) rectangle (0.5,0.7);
  \draw[->, thick, red] (0,0.35) -- ++(0,1.2) node[above] {$N$};
  \draw[->, thick, orange] (0.5,0.35) -- ++(1,0) node[right] {$f$};
\end{scope}
\draw[->, thick, blue] (2.75,1.75) -- ++(0,-1.5) node[below] {$mg$};
\draw (0.9,0) arc (0:26.57:0.9) node[midway, right] {$\theta$};
\end{tikzpicture}
```

Convex lens (ray optics):

```tikz
\begin{tikzpicture}[>=latex, scale=0.8]
\draw[->] (-6,0) -- (6,0) node[right] {axis};
\draw[thick, <->] (0,-2.2) -- (0,2.2);
\fill (-2,0) circle (1.5pt) node[below] {$F$} (2,0) circle (1.5pt) node[below] {$F'$};
\draw[->, very thick, blue] (-4,0) -- (-4,1.2) node[above] {object};
\draw[red] (-4,1.2) -- (0,1.2) -- (6,-2.4);
\draw[red] (-4,1.2) -- (0,0) -- (6,-1.8);
\draw[->, very thick, blue!60] (4,0) -- (4,-1.2) node[below] {image};
\end{tikzpicture}
```

Structural formula (chemfig):

```tikz
\begin{tikzpicture}
\node {\chemfig{*6((-OH)=-=-=)}};
\end{tikzpicture}
```

Function graph (pgfplots):

```tikz
\begin{tikzpicture}
\begin{axis}[axis lines=middle, xlabel=$x$, ylabel=$y$, samples=100, domain=-3:3, width=8cm, height=6cm]
\addplot[blue, thick] {x^3 - 3*x};
\addplot[red, dashed] {3*x^2 - 3};
\end{axis}
\end{tikzpicture}
```

If it doesn't compile, the block shows the end of the TeX log: fix the reported line. Keep pictures small (TeX on a phone is slower than on a PC): avoid huge `samples=` and deep `\foreach` loops.

---

## 7. Mermaid: ```` ```mermaid ````

Mermaid 10.9.8 syntax, always this exact version (flowchart, sequenceDiagram, classDiagram, stateDiagram-v2, erDiagram, gantt, pie, quadrantChart, mindmap, timeline, gitGraph, sankey-beta, xychart-beta, block-beta). With the bundle it works offline; without it the same version loads from the internet. Newer Mermaid 11 diagrams (architecture, packet, kanban, radar) are not available.

---

## Plain math

`$...$` and `$$...$$` are drawn natively with JLaTeXMath (most of LaTeX math: fractions, matrices, integrals, `\overset`, `\xrightarrow`, `\boxed`, colors). Prefer it for equations; use the blocks above only for pictures.
