'use strict';

/*
 * シフト管理システムの JavaScript（10_基本設計書 5.2 の表の動きだけ）。
 * data- 属性を目印にして動きを付ける。Ajax（fetch）は使わない。
 */

/** コピーしたあと、ボタンの文字を戻すまでの時間（ミリ秒） */
const COPY_MESSAGE_MILLIS = 2000;

/** 1日分の入力欄（SB-03・SM-06-1）の中で、○×のラジオ（入れ方の切替のラジオは除く）を返す。 */
function availabilityRadios(day) {
    return day.querySelectorAll('input[type="radio"]:not(.js-mode-radio)');
}

/** 1日分の入力欄が「時間指定」を選んでいるか。 */
function isTimeMode(day) {
    const checked = day.querySelector('.js-mode-radio:checked');
    return checked !== null && checked.value === 'TIME';
}

/** 入れ方（枠ごと・時間指定）に合わせて、時刻のプルダウンと○×のラジオを切り替える。 */
function applyMode(day) {
    const time = isTimeMode(day);
    day.querySelectorAll('[data-time-select]').forEach(function (select) {
        select.hidden = !time;
    });
    availabilityRadios(day).forEach(function (radio) {
        radio.disabled = time;
    });
}

document.addEventListener('DOMContentLoaded', function () {
    // data-confirm：送信の前に確認し、「キャンセル」なら送らない
    document.querySelectorAll('[data-confirm]').forEach(function (button) {
        button.addEventListener('click', function (event) {
            if (!window.confirm(button.dataset.confirm)) {
                event.preventDefault();
            }
        });
    });

    // data-copy-target：入力欄の文字をコピーし、ボタンの文字を2秒間「コピーしました」にする
    document.querySelectorAll('[data-copy-target]').forEach(function (button) {
        button.addEventListener('click', function () {
            const input = document.getElementById(button.dataset.copyTarget);
            if (input === null) {
                return;
            }
            navigator.clipboard.writeText(input.value).then(function () {
                const original = button.textContent;
                button.textContent = 'コピーしました';
                window.setTimeout(function () {
                    button.textContent = original;
                }, COPY_MESSAGE_MILLIS);
            });
        });
    });

    // data-auto-submit：プルダウン・日付を変えたら、その GET フォームを送る
    document.querySelectorAll('[data-auto-submit]').forEach(function (field) {
        field.addEventListener('change', function () {
            if (field.form !== null) {
                field.form.submit();
            }
        });
    });

    // data-auto-navigate：プルダウンを変えたら「URLの前半＋選んだ値」へ移動する
    document.querySelectorAll('[data-auto-navigate]').forEach(function (select) {
        select.addEventListener('change', function () {
            window.location.href = select.dataset.autoNavigate + encodeURIComponent(select.value);
        });
    });

    // data-fill：「全部○」「全部×」。枠ごとの日の、使えるラジオをすべてチェックする
    document.querySelectorAll('[data-fill]').forEach(function (button) {
        button.addEventListener('click', function () {
            const scope = button.closest('form') || document;
            scope.querySelectorAll('[data-request-day]').forEach(function (day) {
                if (isTimeMode(day)) {
                    return;
                }
                availabilityRadios(day).forEach(function (radio) {
                    if (!radio.disabled && radio.value === button.dataset.fill) {
                        radio.checked = true;
                    }
                });
            });
        });
    });

    // .js-mode-radio：時間指定の日は時刻のプルダウンを出して○×を使えなくする。枠ごとに戻したら逆にする
    document.querySelectorAll('.js-mode-radio').forEach(function (radio) {
        radio.addEventListener('change', function () {
            const day = radio.closest('[data-request-day]');
            if (day !== null) {
                applyMode(day);
            }
        });
    });

    // data-print：印刷する
    document.querySelectorAll('[data-print]').forEach(function (button) {
        button.addEventListener('click', function () {
            window.print();
        });
    });

    // data-close：画面を閉じる（SM-06 から別タブで開くため）
    document.querySelectorAll('[data-close]').forEach(function (button) {
        button.addEventListener('click', function () {
            window.close();
        });
    });

    // data-once：送信したらボタンを押せなくし、「作成中…」と表示する（二重送信の防止）
    document.querySelectorAll('[data-once]').forEach(function (button) {
        if (button.form === null) {
            return;
        }
        button.form.addEventListener('submit', function () {
            // 送信の処理が始まってから止める（先に disabled にすると送信そのものが止まる）
            window.setTimeout(function () {
                button.disabled = true;
                button.textContent = '作成中…';
            }, 0);
        });
    });
});
