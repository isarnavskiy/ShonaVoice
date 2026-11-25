if (!Element.prototype.replaceChildren) {
  Element.prototype.replaceChildren = function (...children) {
    this.textContent = '';
    this.append(...children);
  };
}
if (!window.AbortController) {
  window.AbortController = class {
    constructor() {
      this.signal = document.createDocumentFragment();
      this.signal.aborted = false;
    }
    abort() {
      if (this.signal.aborted) return;
      this.signal.aborted = true;
      this.signal.dispatchEvent(new Event('abort'));
    }
  };
}
if (!Promise.prototype.finally) {
  Promise.prototype.finally = function (onFinally) {
    if (typeof onFinally !== 'function') return this.then(onFinally, onFinally);
    return this.then(value => Promise.resolve(onFinally()).then(() => value), reason => Promise.resolve(onFinally()).then(() => {
      throw reason;
    }));
  };
}
(() => {
  'use strict';

  const native = window.NikitaPlatform;
  const requests = new Map();
  let serial = 0;
  let speech = null;
  function request(start, event, signal) {
    const id = ++serial;
    let abort;
    const promise = new Promise((resolve, reject) => {
      function finish(value, error) {
        if (!requests.delete(id)) return;
        if (signal) signal.removeEventListener('abort', abort);
        if (error) reject(error);else resolve(value);
      }
      abort = () => {
        if (!requests.has(id)) return;
        finish('', new DOMException('Операция остановлена.', 'AbortError'));
        native.cancel(id);
      };
      requests.set(id, ({
        type,
        value
      }) => {
        if (type === 'result') finish(value);else if (type === 'error') finish('', new Error(value));else if (event) event(type, value);
      });
      try {
        if (signal !== null && signal !== void 0 && signal.aborted) {
          abort();
          return;
        }
        signal === null || signal === void 0 || signal.addEventListener('abort', abort, {
          once: true
        });
        start(id);
      } catch (error) {
        finish('', error);
      }
    });
    return {
      id,
      promise,
      abort
    };
  }
  function voiceInput(BrowserInput) {
    return class AndroidVoiceInput extends BrowserInput {
      start() {
        if (this.phase !== 'idle') return;
        this.setPhase('requesting');
        this.onNotice('');
        const audioParts = [];
        const job = request(id => native.record(id, this.getLang()), (type, value) => {
          if (type === 'recording') {
            this.onAudio(null);
            if (this.phase !== 'finishing') this.setPhase('recording');
          } else if (type === 'finishing') {
            this.setPhase('finishing');
            this.onNotice('Обработка записи…');
          } else if (type === 'audio-part' || type === 'audio') {
            const binary = atob(value);
            const bytes = new Uint8Array(binary.length);
            for (let i = 0; i < bytes.length; i++) bytes[i] = binary.charCodeAt(i);
            audioParts.push(bytes);
            if (type === 'audio') {
              this.onAudio(new Blob(audioParts, {
                type: 'audio/wav'
              }), {
                transcribed: true
              });
              audioParts.length = 0;
            }
          }
        });
        this.session = job;
        job.promise.then(text => {
          if (this.session !== job) return;
          this.session = null;
          this.setPhase('idle');
          if (text) this.onText(text);
        }, error => {
          if (this.session !== job) return;
          this.session = null;
          this.setPhase('idle');
          this.onNotice(error.message);
        });
      }
      stop() {
        if (!this.session || this.phase === 'finishing') return;
        this.setPhase('finishing');
        native.finish(this.session.id);
      }
      cancel() {
        this.press = null;
        if (!this.session) return;
        this.session.abort();
        this.session = null;
        this.setPhase('idle');
      }
    };
  }
  window.NikitaDevice = Object.freeze({
    voiceInput,
    status() {
      return JSON.parse(native.status());
    },
    translate(text, from, to, signal) {
      return request(id => native.translate(id, text, from, to), null, signal).promise;
    },
    speak(text, lang) {
      const job = request(id => native.speak(id, text, lang));
      speech = job;
      return job.promise.finally(() => {
        if (speech === job) speech = null;
      });
    },
    stopSpeech() {
      if (speech) speech.abort();
      speech = null;
    },
    receive(event) {
      const listener = requests.get(event.id);
      if (listener) listener(event);
    }
  });
})();
