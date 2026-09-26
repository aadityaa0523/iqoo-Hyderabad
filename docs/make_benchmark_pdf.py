"""Builds docs/Nadaka_NPU_Benchmark_Report.pdf from the raw benchmark tables (bench_*.md from the phone).

    python docs/make_benchmark_pdf.py <baseline bench.md> <optimised bench.md>
"""
import sys
from pathlib import Path

from reportlab.graphics.charts.barcharts import HorizontalBarChart
from reportlab.graphics.shapes import Drawing, String
from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.platypus import KeepTogether, PageBreak, Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle

NAVY = colors.HexColor("#0E1A2B")
AMBER = colors.HexColor("#FFB300")
GREY = colors.HexColor("#F1F3F5")


def parse(path):
    """Rows of the benchmark table: dict(model, backend, deleg, init, stage, median, p90, p95, min, max)."""
    rows = []
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) == 10 and cells[0] in ("YOLOX", "Depth"):
            m, b, d, init, stage, *v = cells
            rows.append(dict(model=m, backend=b, deleg=d, init=float(init), stage=stage,
                             median=float(v[0]), p90=float(v[1]), p95=float(v[2]), min=float(v[3]), max=float(v[4])))
    return rows


def get(rows, model, backend, stage):
    return next(r for r in rows if r["model"] == model and r["backend"] == backend and r["stage"] == stage)


styles = getSampleStyleSheet()
H1 = ParagraphStyle("h1", parent=styles["Heading1"], textColor=NAVY, fontSize=15, spaceBefore=10, spaceAfter=6)
H2 = ParagraphStyle("h2", parent=styles["Heading2"], textColor=NAVY, fontSize=12, spaceBefore=8, spaceAfter=4)
BODY = ParagraphStyle("b", parent=styles["BodyText"], fontSize=9.5, leading=13)
SMALL = ParagraphStyle("s", parent=BODY, fontSize=8, leading=10, textColor=colors.HexColor("#444444"))
CELL = ParagraphStyle("c", parent=BODY, fontSize=8, leading=10)


def table(data, widths, highlight_rows=(), header=True):
    head = ParagraphStyle("hc", parent=CELL, textColor=colors.white, fontName="Helvetica-Bold")
    data = [[Paragraph(str(c), head if (header and r == 0) else CELL) for c in row] for r, row in enumerate(data)]
    t = Table(data, colWidths=widths, repeatRows=1 if header else 0)
    st = [("GRID", (0, 0), (-1, -1), 0.4, colors.HexColor("#C9CED6")),
          ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
          ("TOPPADDING", (0, 0), (-1, -1), 3), ("BOTTOMPADDING", (0, 0), (-1, -1), 3)]
    if header:
        st += [("BACKGROUND", (0, 0), (-1, 0), NAVY), ("TEXTCOLOR", (0, 0), (-1, 0), colors.white)]
    for i in range(1, len(data)):
        if i % 2 == 0:
            st.append(("BACKGROUND", (0, i), (-1, i), GREY))
    for r in highlight_rows:
        st.append(("BACKGROUND", (0, r), (-1, r), colors.HexColor("#FFF3CC")))
    t.setStyle(TableStyle(st))
    return t


def bar_chart(title, labels, values, width=170 * mm, unit="ms"):
    h = 18 * len(labels) + 40
    d = Drawing(width, h)
    c = HorizontalBarChart()
    c.x, c.y, c.width, c.height = 90, 20, width - 140, h - 40
    c.data = [values]
    c.categoryAxis.categoryNames = labels
    c.categoryAxis.labels.fontSize = 8
    c.valueAxis.valueMin = 0
    c.valueAxis.labels.fontSize = 7
    c.bars[0].fillColor = AMBER
    c.bars[0].strokeColor = NAVY
    c.barLabelFormat = "%.1f " + unit
    c.barLabels.fontSize = 7
    c.barLabels.nudge = 18
    d.add(c)
    d.add(String(0, h - 12, title, fontSize=9, fontName="Helvetica-Bold", fillColor=NAVY))
    return d


def build(cold_path, opt_path, out):
    cold, opt = parse(cold_path), parse(opt_path)
    story = []
    story += [Paragraph("Nadaka: Qualcomm NPU Verification &amp; Benchmark Report", ParagraphStyle(
        "t", parent=styles["Title"], textColor=NAVY, fontSize=19)),
        Paragraph("On-device inference on the iQOO I2501 &middot; Snapdragon SM8850 (8 Elite Gen 5) &middot; Hexagon HTP V81 &middot; Android 16", BODY),
        Paragraph("iQOO Hackathon 2026, Hyderabad &middot; measured 26 Sep 2026", SMALL), Spacer(1, 8)]

    story.append(Paragraph("1. Method", H1))
    story.append(Paragraph(
        "A dedicated benchmark screen (<i>BenchActivity</i>) runs with no camera, so nothing else competes for the NPU. "
        "For every model and backend it creates a fresh LiteRT interpreter, runs 10 warm-up inferences and then "
        "100 timed runs on the same real 480&times;640 camera frame from this phone. Pre-processing, inference and "
        "post-processing are timed separately with <i>System.nanoTime</i>. The number of delegated operations is read "
        "from the runtime's own log line (&quot;Replacing X out of Y node(s) with delegate&quot;), not inferred from the "
        "delegate having been created. All times are milliseconds.", BODY))
    story.append(Spacer(1, 4))
    story.append(table([
        ["Item", "Value"],
        ["Runtime", "LiteRT 1.4.2 + Qualcomm QNN LiteRT delegate 2.50.0 (HTP backend), GPU delegate, XNNPACK CPU (4 threads)"],
        ["Object detector", "YOLOX, Qualcomm AI Hub release 0.63.0, w8a8, input uint8 1&times;640&times;640&times;3"],
        ["Depth model", "Depth Anything V2, Qualcomm AI Hub release 0.63.0, FP32 TFLite executed at FP16 on HTP, input 1&times;518&times;518&times;3"],
        ["Post-processing", "YOLOX: dequantise + class-aware NMS. Depth: pool to 48&times;32 grid + floor-ruler drop-off analysis"],
        ["Runs", "10 warm-up + 100 timed per configuration; statistics: median, P90, P95, min, max"],
    ], [38 * mm, 132 * mm]))

    story.append(Paragraph("2. Verification of NPU execution", H1))
    story.append(table([
        ["Check", "Result"],
        ["Delegate configuration", "QnnDelegate.Options: HTP_BACKEND, skel dir = nativeLibraryDir, HTP_PERFORMANCE_SUSTAINED_HIGH_PERFORMANCE, FP16 precision for depth"],
        ["HTP libraries", "libQnnHtp, libQnnHtpPrepare, libQnnHtpV75/V79/V81 Skel + Stub, libQnnSystem, libQnnTFLiteDelegate: present in the APK and in the installed native library directory"],
        ["YOLOX delegation", f"{get(opt, 'YOLOX', 'HTP', 'total')['deleg']}"],
        ["Depth delegation", f"{get(opt, 'Depth', 'HTP', 'total')['deleg']}"],
        ["Graph cache", "Was not working (no model token; only 20 B - 2 KB metadata files). Fixed with setModelToken: compiled graphs of 9.6 MB (YOLOX) and 52.7 MB (depth) now written to cacheDir"],
        ["Debug UI", "Header shows YOLO backend + latency and Depth backend + latency separately"],
    ], [38 * mm, 132 * mm]))

    story.append(Paragraph("3. Summary (final build)", H1))
    rows = [["Model", "Backend", "Precision", "Delegated ops", "Inference median / P95", "Total median / P95"]]
    hl = []
    for m, prec in (("YOLOX", {"CPU": "w8a8", "GPU": "w8a8", "HTP": "w8a8"}), ("Depth", {"CPU": "FP32", "GPU": "FP32", "HTP": "FP16"})):
        for b in ("CPU", "GPU", "HTP"):
            i, t = get(opt, m, b, "inference"), get(opt, m, b, "total")
            deleg = "n/a (CPU)" if b == "CPU" else i["deleg"].split(" (")[0]
            rows.append([m if m == "YOLOX" else "Depth Anything V2", b, prec[b], deleg,
                         f"{i['median']:.2f} / {i['p95']:.2f}", f"{t['median']:.2f} / {t['p95']:.2f}"])
            if b == "HTP":
                hl.append(len(rows) - 1)
    rows.append(["Depth Anything V2", "HTP", "INT8", "-", "not available (section 7)", "-"])
    story.append(table(rows, [30 * mm, 17 * mm, 18 * mm, 26 * mm, 40 * mm, 39 * mm], highlight_rows=hl))
    story.append(Spacer(1, 6))
    yi = {b: get(opt, "YOLOX", b, "inference")["median"] for b in ("CPU", "GPU", "HTP")}
    di = {b: get(opt, "Depth", b, "inference")["median"] for b in ("CPU", "GPU", "HTP")}
    story.append(Paragraph(
        f"<b>Measured inference speed-up of HTP:</b> YOLOX {yi['CPU'] / yi['HTP']:.0f}&times; vs CPU and "
        f"{yi['GPU'] / yi['HTP']:.0f}&times; vs GPU; Depth Anything V2 {di['CPU'] / di['HTP']:.0f}&times; vs CPU and "
        f"{di['GPU'] / di['HTP']:.1f}&times; vs GPU. The GPU delegate left 2 of 317 YOLOX operations on the CPU.", BODY))
    story.append(Spacer(1, 6))
    story.append(KeepTogether([
        bar_chart("YOLOX total latency per frame (median, ms)", ["CPU", "GPU", "HTP"],
                  [get(opt, "YOLOX", b, "total")["median"] for b in ("CPU", "GPU", "HTP")]),
        bar_chart("Depth Anything V2 total latency per frame (median, ms)", ["CPU", "GPU", "HTP"],
                  [get(opt, "Depth", b, "total")["median"] for b in ("CPU", "GPU", "HTP")])]))

    story.append(Paragraph("4. Optimisations applied and their measured effect", H1))
    yb, yo = get(cold, "YOLOX", "HTP", "pre"), get(opt, "YOLOX", "HTP", "pre")
    db, do = get(cold, "Depth", "HTP", "pre"), get(opt, "Depth", "HTP", "pre")
    story.append(table([
        ["Change", "Metric", "Before", "After"],
        ["Pre-processing: fill an array, one bulk copy (was one ByteBuffer.put per value)", "YOLOX HTP pre-processing median", f"{yb['median']:.2f} ms", f"{yo['median']:.2f} ms"],
        ["", "YOLOX HTP total median", f"{get(cold, 'YOLOX', 'HTP', 'total')['median']:.2f} ms", f"{get(opt, 'YOLOX', 'HTP', 'total')['median']:.2f} ms"],
        ["", "Depth HTP pre-processing median", f"{db['median']:.2f} ms", f"{do['median']:.2f} ms"],
        ["", "Depth HTP total median", f"{get(cold, 'Depth', 'HTP', 'total')['median']:.2f} ms", f"{get(opt, 'Depth', 'HTP', 'total')['median']:.2f} ms"],
        ["QNN graph cache (model token)", "YOLOX HTP interpreter init", f"{get(cold, 'YOLOX', 'HTP', 'total')['init']:.0f} ms (cold)", f"{get(opt, 'YOLOX', 'HTP', 'total')['init']:.0f} ms (cached)"],
        ["", "Depth HTP interpreter init", f"{get(cold, 'Depth', 'HTP', 'total')['init']:.0f} ms (cold)", f"{get(opt, 'Depth', 'HTP', 'total')['init']:.0f} ms (cached)"],
    ], [62 * mm, 50 * mm, 29 * mm, 29 * mm]))
    story.append(Spacer(1, 4))
    story.append(Paragraph("The delegate configuration, models, camera pipeline and tensor values are unchanged; only the "
                           "host-side tensor copy and the cache token changed.", SMALL))

    for title, rows_ in (("5. Full results: final build", opt), ("6. Full results: baseline (before the fixes, cold cache)", cold)):
        story.append(Paragraph(title, H1))
        data = [["Model", "Backend", "Delegated ops", "Init", "Stage", "Median", "P90", "P95", "Min", "Max"]]
        for r in rows_:
            data.append([r["model"], r["backend"], r["deleg"].replace(" (", "<br/>("), f"{r['init']:.0f}", r["stage"],
                         f"{r['median']:.2f}", f"{r['p90']:.2f}", f"{r['p95']:.2f}", f"{r['min']:.2f}", f"{r['max']:.2f}"])
        story.append(table(data, [15 * mm, 18 * mm, 33 * mm, 12 * mm, 18 * mm, 15 * mm, 15 * mm, 15 * mm, 14 * mm, 15 * mm]))

    story.append(Paragraph("7. Depth INT8 investigation", H1))
    story.append(table([
        ["Qualcomm AI Hub model (release 0.63.0)", "Float", "Quantized"],
        ["Depth Anything V2 (production)", "ONNX, QNN-DLC, TFLite", "w8a16 as ONNX / QNN-DLC only (no TFLite)"],
        ["Depth Anything (V1)", "ONNX, QNN-DLC, TFLite", "w8a16 as ONNX / QNN-DLC only (no TFLite)"],
        ["Depth Anything V3", "ONNX, QNN-DLC, TFLite", "none"],
        ["MiDaS V2 (different model, out of scope)", "ONNX, QNN-DLC, TFLite", "w8a8 incl. TFLite"],
    ], [66 * mm, 50 * mm, 54 * mm]))
    story.append(Spacer(1, 4))
    story.append(Paragraph(
        "No INT8 LiteRT build of Depth Anything V2 is published, so depth_int8.tflite was not created and the "
        "scene-by-scene FP16 vs INT8 comparison and drop-off impact test have nothing to compare yet. Producing one "
        "would require a calibration set of recorded frames from the target scenes (flat floor, steps, stairs, kerb, "
        "ramp, shadow, puddle, shiny floor, doormat, threshold, wall, low light), plus an AI Hub account or a local "
        "ONNX to TFLite post-training quantization toolchain. Qualcomm itself ships this ViT (DINOv2) backbone at w8a16 "
        "rather than full INT8.", BODY))

    story.append(Paragraph("8. Recommendation", H1))
    dh = get(opt, "Depth", "HTP", "inference")["median"]
    dt = get(opt, "Depth", "HTP", "total")["median"]
    story.append(Paragraph(
        f"<b>Keep the production depth model at FP16 on HTP.</b> HTP inference is {dh:.1f} ms of a {dt:.1f} ms depth "
        f"step; even a perfect 2&times; INT8 speed-up would save about {dh / 2:.0f} ms per depth frame, while the drop-off "
        "detector depends on sharp depth discontinuities that quantization tends to blur. Revisit only if an INT8 "
        "LiteRT model becomes available and passes the scene-by-scene drop-off test.", BODY))
    story.append(Spacer(1, 4))
    story.append(Paragraph("Limitations: one device, one input frame, room temperature, device not on a controlled "
                           "thermal rig; memory usage was not measured in this run. Numbers can vary with heat and "
                           "background load.", SMALL))

    def footer(canvas, doc):
        canvas.saveState()
        canvas.setFont("Helvetica", 7.5)
        canvas.setFillColor(colors.HexColor("#666666"))
        canvas.drawString(15 * mm, 10 * mm, "Nadaka - NPU verification & benchmark")
        canvas.drawRightString(A4[0] - 15 * mm, 10 * mm, f"Page {doc.page}")
        canvas.restoreState()

    SimpleDocTemplate(out, pagesize=A4, leftMargin=15 * mm, rightMargin=15 * mm, topMargin=15 * mm, bottomMargin=16 * mm,
                      title="Nadaka NPU Verification & Benchmark", author="Nadaka team").build(story, onFirstPage=footer, onLaterPages=footer)


if __name__ == "__main__":
    build(sys.argv[1], sys.argv[2], str(Path(__file__).with_name("Nadaka_NPU_Benchmark_Report.pdf")))
    print("ok")
