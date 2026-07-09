#!/usr/bin/env python3
from pathlib import Path
import subprocess
import sys
import tempfile


ROOT = Path(__file__).resolve().parents[1]
JAVA_CONNECTIONS = ROOT / "TMessagesProj/src/main/java/org/telegram/tgnet/ConnectionsManager.java"
TL_PARSE_EXCEPTION = ROOT / "TMessagesProj/src/main/java/org/telegram/tgnet/TLParseException.java"
TLRPC = ROOT / "TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java"
FILE_STREAM = ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/FileStreamLoadOperation.java"
RUNTIME_VERIFIER = ROOT / "Tools/verify_mtproxy_runtime_logs.py"
MTPROXY_ALL = ROOT / "Tools/check_mtproxy_all.py"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace")


def require(condition: bool, message: str, failures: list[str]) -> None:
    if not condition:
        failures.append(message)


def runtime_log(extra_lines: list[str]) -> str:
    lines = [
        "logcat.txt:1: 06-25 20:31:30.000 connection(0x1) mtproxy_transport snapshot event=open transport_state=prepared epoll_registered=0 admission_active=0 tcp_gate_active=0",
        "logcat.txt:2: 06-25 20:31:30.010 connection(0x1) mtproxy_startup server_hello_hmac_ok bytes=196",
        "logcat.txt:3: 06-25 20:31:30.020 connection(0x1) mtproxy_startup endpoint_handshake_ok reason=server_hello_hmac_ok",
        "logcat.txt:4: 06-25 20:31:30.030 connection(0x1) mtproxy_startup first_tls_app_recv payload=1015",
        "logcat.txt:5: 06-25 20:31:30.040 connection(0x1) mtproxy_startup endpoint_data_path_success reason=first_tls_app_recv",
    ]
    lines.extend(extra_lines)
    lines.append("logcat.txt:99: 06-25 20:31:31.000 connection(0x1) mtproxy_disconnect reason=2 transport_state=closing epoll_registered=1 admission_active=0 tcp_gate_active=0")
    return "\n".join(lines) + "\n"


def run_runtime_checks(failures: list[str]) -> None:
    with tempfile.TemporaryDirectory() as tmp:
        tmp_path = Path(tmp)
        cases = [
            (
                "bad_partial.txt",
                ["logcat.txt:10: 06-25 20:31:30.050 D/tmessages received packet size less(16636) then message size(32882)"],
                "partial packet assembly must not use failure-looking wording",
                False,
            ),
            (
                "bad_tlparse.txt",
                ["logcat.txt:10: 06-25 20:31:30.050 E/tmessages: org.telegram.tgnet.TLParseException: can't parse magic cd78e586 in org.telegram.tgnet.TLRPC$upload_File. Source: NETWORK"],
                "upload_File TLParseException must include context and avoid E/FATAL",
                False,
            ),
            (
                "bad_filestream.txt",
                ["logcat.txt:10: 06-25 20:31:30.050 E/tmessages: FileStreamLoadOperation 1 open operation=..."],
                "FileStreamLoadOperation lifecycle logs must not use E/tmessages",
                False,
            ),
            (
                "good_drop.txt",
                ["logcat.txt:10: 06-25 20:31:30.050 D/tmessages: tl_parse_drop_answer_ignored raw_constructor=0xcd78e586 expected_response=TLRPC.upload_File{0x96a18d5,0xf18cda44} request=TL_upload_getFile request_token=7 request_msg_id=0x11 conType=2 dc=4 file=TL_inputDocumentFileLocation:id=1 offset=0 limit=131072 action=ignored"],
                "",
                True,
            ),
        ]
        for filename, extra_lines, expected, should_pass in cases:
            path = tmp_path / filename
            path.write_text(runtime_log(extra_lines), encoding="utf-8")
            result = subprocess.run([sys.executable, str(RUNTIME_VERIFIER), str(path)], cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=False)
            if should_pass:
                require(result.returncode == 0, result.stderr.strip() or f"runtime verifier must accept {filename}", failures)
            else:
                require(result.returncode != 0 and expected in result.stderr, f"runtime verifier must reject {filename}: {expected}", failures)


def main() -> int:
    failures: list[str] = []
    java_connections = read(JAVA_CONNECTIONS)
    tl_parse_exception = read(TL_PARSE_EXCEPTION)
    tlrpc = read(TLRPC)
    file_stream = read(FILE_STREAM)
    runtime_verifier = read(RUNTIME_VERIFIER)
    mtproxy_all = read(MTPROXY_ALL)

    require(
        "logTlParseExceptionContext(" in java_connections
        and "tl_parse_exception_context" in java_connections
        and "raw_constructor=0x" in java_connections
        and "expected_response=" in java_connections
        and "request_token=" in java_connections
        and "request_msg_id=0x" in java_connections
        and "conType=" in java_connections
        and "offset=" in java_connections
        and "limit=" in java_connections
        and "file=" in java_connections,
        "ConnectionsManager must log TL parse failures with constructor, expected type, token, message id, conType, file and offset context",
        failures,
    )
    require(
        "isRpcDropAnswerConstructor(magic)" in java_connections
        and "tl_parse_drop_answer_ignored" in java_connections
        and "action=ignored" in java_connections
        and "return;" in java_connections,
        "ConnectionsManager must ignore rpc_answer_dropped* constructors as cancel/drop-safe responses instead of fataling upload.getFile",
        failures,
    )
    require(
        "FileLog.fatal(e2);" not in java_connections
        or java_connections.find("logTlParseExceptionContext(") < java_connections.find("FileLog.fatal(e2);"),
        "ConnectionsManager must write TL parse context before any remaining fatal path",
        failures,
    )
    require(
        "isRpcDropAnswerConstructor" in tl_parse_exception
        and "tl_parse_drop_answer" in tl_parse_exception
        and "FileLog.d" in tl_parse_exception
        and "constructorId != 0xcd78e586" not in tl_parse_exception,
        "TLParseException must downgrade rpc_answer_dropped* constructors to debug and avoid constructor-specific ad-hoc fatal filters",
        failures,
    )
    require(
        "case 0x96a18d5:" in tlrpc
        and "case 0xf18cda44:" in tlrpc
        and "case 0xa99fca4f:" in tlrpc
        and "case 0xeea8e46e:" in tlrpc,
        "TLRPC must know the valid upload.getFile/upload.getCdnFile response constructors",
        failures,
    )
    require(
        'FileLog.e("FileStreamLoadOperation ' not in file_stream
        and 'FileLog.d("FileStreamLoadOperation ' in file_stream,
        "FileStreamLoadOperation open/close lifecycle logs must be debug, not E/tmessages",
        failures,
    )
    require(
        "received packet size less" in runtime_verifier
        and "TLParseException" in runtime_verifier
        and "tl_parse_drop_answer_ignored" in runtime_verifier
        and "FileStreamLoadOperation lifecycle logs must not use E/tmessages" in runtime_verifier,
        "runtime verifier must reject partial-packet noise, upload_File TLParseException fatal logs, and FileStreamLoadOperation E logs",
        failures,
    )
    require(
        '"check_mtproxy_tlparse_upload_context.py"' in mtproxy_all,
        "full MTProxy guard suite must include TL parse upload context guard",
        failures,
    )
    run_runtime_checks(failures)

    if failures:
        print("MTProxy TL parse upload context guard failed:", file=sys.stderr)
        for failure in failures:
            print(f" - {failure}", file=sys.stderr)
        return 1

    print("MTProxy TL parse upload context guard passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
