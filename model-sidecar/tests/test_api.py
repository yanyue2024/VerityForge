import os

os.environ["MODEL_PRELOAD"] = "false"

from fastapi.testclient import TestClient

from model_sidecar import main


def test_health_does_not_expose_host_model_paths() -> None:
    with TestClient(main.app) as client:
        response = client.get("/health")

    assert response.status_code == 200
    payload = response.json()
    assert payload["embedding"]["name"] == "BAAI/bge-m3"
    assert payload["embedding"]["dimension"] == 1024
    assert "path" not in payload["embedding"]
    assert "path" not in payload["rerank"]


def test_embeddings_uses_openai_compatible_shape(monkeypatch) -> None:
    monkeypatch.setattr(main.embedding_runtime, "embed", lambda texts: [[0.1] * 1024 for _ in texts])

    with TestClient(main.app) as client:
        response = client.post("/v1/embeddings", json={"input": ["alpha", "beta"]})

    assert response.status_code == 200
    payload = response.json()
    assert [item["index"] for item in payload["data"]] == [0, 1]
    assert payload["model"] == "BAAI/bge-m3"
    assert len(payload["data"][0]["embedding"]) == 1024


def test_embeddings_rejects_a_dimension_other_than_bge_m3() -> None:
    with TestClient(main.app) as client:
        response = client.post(
            "/v1/embeddings",
            json={"input": "alpha", "dimensions": 512},
        )

    assert response.status_code == 400
    assert response.json()["detail"] == "requested dimensions do not match the model"


def test_rerank_preserves_source_indexes(monkeypatch) -> None:
    monkeypatch.setattr(
        main.rerank_runtime,
        "rerank",
        lambda query, documents: [
            {"index": 1, "document": {"text": documents[1]}, "relevance_score": 0.9},
            {"index": 0, "document": {"text": documents[0]}, "relevance_score": 0.2},
        ],
    )

    with TestClient(main.app) as client:
        response = client.post(
            "/rerank",
            json={"query": "target", "documents": ["less relevant", "target evidence"]},
        )

    assert response.status_code == 200
    assert [item["index"] for item in response.json()["results"]] == [1, 0]
