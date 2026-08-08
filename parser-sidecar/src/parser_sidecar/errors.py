class ParserSidecarError(Exception):
    status_code = 500
    code = "parser_error"


class DownloadError(ParserSidecarError):
    status_code = 502
    code = "source_download_failed"


class UploadError(ParserSidecarError):
    status_code = 502
    code = "result_upload_failed"


class PayloadTooLargeError(ParserSidecarError):
    status_code = 413
    code = "document_too_large"


class UnsupportedDocumentError(ParserSidecarError):
    status_code = 415
    code = "unsupported_document"


class ParseError(ParserSidecarError):
    status_code = 422
    code = "document_parse_failed"


class UnsupportedProfileError(ParserSidecarError):
    status_code = 422
    code = "unsupported_parser_profile"


class ProfileUnavailableError(ParserSidecarError):
    status_code = 503
    code = "parser_profile_unavailable"

