from types import SimpleNamespace

import pytest

from model_sidecar.runtime import _validate_embedding_dimension


def test_accepts_bge_m3_dimension() -> None:
    model = SimpleNamespace(config=SimpleNamespace(hidden_size=1024))

    _validate_embedding_dimension(model, 1024)


def test_rejects_checkpoint_with_incompatible_dimension() -> None:
    model = SimpleNamespace(config=SimpleNamespace(hidden_size=512))

    with pytest.raises(RuntimeError, match="expected 1024, got 512"):
        _validate_embedding_dimension(model, 1024)
