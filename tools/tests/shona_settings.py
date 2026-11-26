"""Run with python tools/tests/shona_settings.py after installing Playwright and Chromium.

Use python -m playwright install chromium or set CHROME_BIN to a local binary.
The test bridge exercises the shipped UI without requiring model files.
"""
import os
from pathlib import Path

from playwright.sync_api import expect, sync_playwright

ASSETS = Path(__file__).resolve().parents[2] / "app/src/main/assets"
URL = "https://nikita.invalid/"
VOICES = [
    {"id": "mms-int8", "name": "MMS · компактная модель"},
    {"id": "everyvoice", "name": "EveryVoice · голос шона"},
]
BRIDGE = """
(() => {
  const requests = new Set();
  const emit = (id, type, value='') => window.NikitaDevice.receive({id, type, value});
  window.calls = [];
  window.NikitaPlatform = {
    status() {
      return JSON.stringify({
        translation: true, asr: true, shonaTts: true, defaultShonaVoice: 'everyvoice',
        shonaVoices: JSON.parse(localStorage.getItem('testVoices') || '[]')
      });
    },
    hasShonaNumberGroups(text) { return text.includes('14:30'); },
    record(id) {
      window.recording = id;
      Promise.resolve().then(() => emit(id, 'recording'));
    },
    finish(id) {
      emit(id, 'finishing');
      Promise.resolve().then(() => emit(id, 'result', 'Запись 004.'));
    },
    translate(id, text, from, to) {
      window.calls.push({kind: 'translate', text, from, to});
      const shona = text.includes('14:30') ? 'Nguva 14:30.' : 'Kodhi 004.';
      Promise.resolve().then(() => emit(id, 'result', to === 'sn' ? shona : 'Код 004.'));
    },
    cancel(id) { window.calls.push({kind:'cancel', id}); requests.delete(id); },
    speak(id, text, lang) {
      window.calls.push({kind:'speak', text, lang});
      Promise.resolve().then(() => emit(id,'result'));
    },
    speakWithVoice(id, text, lang, voice) {
      window.calls.push({kind:'select', id, text, lang, voice});
      requests.add(id);
    }
  };
  window.finish = (id, error) => {
    if (requests.delete(id)) emit(id, error ? 'error' : 'result', error || '');
  };
})()
"""


def run():
    html = (ASSETS / "index.html").read_text(encoding="utf-8")
    device = (ASSETS / "device.js").read_text(encoding="utf-8")
    html = html.replace("<head>", f"<head><script>{device}</script>", 1)
    with sync_playwright() as tools:
        browser = tools.chromium.launch(executable_path=os.environ.get("CHROME_BIN"), args=["--no-sandbox"])
        context = browser.new_context(viewport={"width": 393, "height": 725}, is_mobile=True, has_touch=True)
        context.add_init_script(BRIDGE)
        context.route(URL, lambda route: route.fulfill(body=html, content_type="text/html; charset=utf-8"))
        page = context.new_page()
        errors = []
        page.on("pageerror", lambda error: errors.append(str(error)))
        page.goto(URL)

        def control(element_id):
            return page.locator("#" + element_id)

        def available(voices):
            page.evaluate("v => localStorage.setItem('testVoices', JSON.stringify(v))", voices)

        def last_call():
            return page.evaluate("window.calls.at(-1)")

        def finish(error=None):
            page.evaluate("([id,error]) => finish(id,error)", [last_call()["id"], error])

        def wait_cancel(request_id):
            page.wait_for_function(
                "id => window.calls.some(call => call.kind==='cancel' && call.id===id)", arg=request_id
            )

        field = control("input")
        def translations():
            return page.evaluate("window.calls.filter(x => x.kind === 'translate').length")

        field.fill("Договор 004.")
        field.press("End")
        field.press("Shift+Enter")
        assert field.input_value().endswith("\n") and translations() == 0
        field.press("Enter")
        expect(control("output")).to_have_text("Kodhi 004.")
        assert translations() == 1
        field.press("Control+Enter")
        assert translations() == 1  # Exact repeat uses the session cache.
        field.fill("Договор 005.")
        field.evaluate("""el => el.dispatchEvent(new InputEvent("beforeinput",
            {inputType: "insertLineBreak", cancelable: true, bubbles: true}))""")
        expect(control("output")).to_have_text("Kodhi 004.")
        assert translations() == 2
        field.fill("")
        field.press("Enter")
        assert translations() == 2

        control("mic").tap()
        expect(control("mic")).to_have_attribute("data-phase", "recording")
        control("mic").tap()
        expect(control("output")).to_have_text("Kodhi 004.")
        assert translations() == 3
        control("settings").tap()
        control("reviewText").check()
        control("close").tap()
        page.reload()
        control("mic").tap()
        expect(control("mic")).to_have_attribute("data-phase", "recording")
        control("mic").tap()
        expect(field).to_have_value("Запись 004.")
        assert translations() == 0
        field.press("Enter")
        expect(control("output")).to_have_text("Kodhi 004.")
        assert translations() == 1

        choice = control("shonaVoice")
        sample = control("previewShona")
        for available_voices in ([], VOICES[:1]):
            available(available_voices)
            control("settings").tap()
            expect(control("shonaVoiceChoice")).to_be_hidden()
            control("close").tap()
        available(VOICES)
        control("settings").tap()
        expect(control("shonaVoiceChoice")).to_be_visible()
        expect(choice).to_have_value("everyvoice")

        sample.tap()
        call = last_call()
        assert (call["voice"], call["text"]) == ("everyvoice", "Mhoro! Makadii?")
        choice.select_option("mms-int8")
        wait_cancel(call["id"])
        expect(sample).to_have_text("Прослушать пример")

        sample.tap()
        request_id = last_call()["id"]
        sample.tap()
        wait_cancel(request_id)
        sample.tap()
        finish("Ошибка примера")
        expect(control("previewNotice")).to_have_text("Ошибка примера")
        sample.tap()
        request_id = last_call()["id"]
        control("close").tap()
        wait_cancel(request_id)

        page.reload()
        control("settings").tap()
        expect(choice).to_have_value("mms-int8")
        control("close").tap()
        for text, output, tip in (
            ("Код 004.", "Kodhi 004.", "по одной цифре"),
            ("В 14:30.", "Nguva 14:30.", "Время и даты читаются группами цифр."),
        ):
            control("input").fill(text)
            control("translate").tap()
            expect(control("output")).to_have_text(output)
            control("listen").tap()
            call = last_call()
            assert (call["voice"], call["text"], call["lang"]) == ("mms-int8", output, "sn")
            expect(control("message")).to_contain_text(tip)
            finish()
            expect(control("listen")).to_have_attribute("title", "Озвучить перевод")

        control("swap").tap()
        control("translate").tap()
        expect(control("output")).to_have_text("Код 004.")
        control("listen").tap()
        assert last_call() == {"kind": "speak", "lang": "ru", "text": "Код 004."}
        available(VOICES[1:])
        control("settings").tap()
        expect(control("shonaVoiceChoice")).to_be_hidden()
        expect(choice).to_have_value("everyvoice")
        assert not errors, errors
        print(f"Chromium {browser.version}: Shona voice selection and numeric guidance passed.")
        browser.close()


if __name__ == "__main__":
    run()
