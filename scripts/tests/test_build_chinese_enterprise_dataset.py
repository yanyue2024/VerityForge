from __future__ import annotations

import importlib.util
from pathlib import Path
import sys
import unittest


SCRIPT_PATH = Path(__file__).resolve().parents[1] / "build-chinese-enterprise-dataset.py"
SPEC = importlib.util.spec_from_file_location("build_chinese_enterprise_dataset", SCRIPT_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Unable to load {SCRIPT_PATH}")
dataset_builder = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = dataset_builder
SPEC.loader.exec_module(dataset_builder)


class DocusaurusConversionTest(unittest.TestCase):
    def test_mdx_cleanup_preserves_angle_brackets_inside_code(self) -> None:
        source = r"""---
title: Hive Catalog
---
import Tabs from '@theme/Tabs';

# Hive Catalog {#hive-catalog}

Use `<hive_metastore_type>` and `<fs_defaultfs>` as placeholders.

```properties
meta.cache.<engine>.<entry>=60
```

<Tabs>Remove this MDX wrapper.</Tabs>
"""

        title, converted = dataset_builder.clean_markdown(source, "hive-catalog.mdx")

        self.assertEqual("Hive Catalog", title)
        self.assertIn("`<hive_metastore_type>`", converted)
        self.assertIn("`<fs_defaultfs>`", converted)
        self.assertIn("meta.cache.<engine>.<entry>=60", converted)
        self.assertNotIn("import Tabs", converted)
        self.assertNotIn("<Tabs>", converted)

    def test_comment_with_inline_code_is_removed_as_one_construct(self) -> None:
        source = """# Guide

<!-- Read about `kubectl` first. -->
保留这段正文，并继续执行。
"""

        _title, converted = dataset_builder.clean_markdown(source, "guide.md")

        self.assertNotIn("kubectl", converted)
        self.assertNotIn("-->", converted)
        self.assertIn("保留这段正文", converted)

    def test_html_breaks_become_boundaries_without_changing_code_literals(self) -> None:
        source = """# CLI reference

<p>Options are:<br/>
FeatureOne=true|false (BETA)<br>
FeatureTwo=true|false (ALPHA)</p>

Use `<br/>` when documenting an HTML break.

```html
first<br/>
second
```
"""

        _title, converted = dataset_builder.clean_markdown(source, "cli.md")

        self.assertIn("Options are:\n\nFeatureOne=true|false", converted)
        self.assertIn("FeatureOne=true|false (BETA)\n\nFeatureTwo=true|false", converted)
        self.assertIn("`<br/>`", converted)
        self.assertIn("first<br/>\nsecond", converted)

    def test_html_breaks_inside_markdown_tables_remain_inline(self) -> None:
        source = r"""---
title: Select
---

| Parameter | Type | Default |
| --- | --- | --- |
| value | string \| string[] \|<br />number \| number[] | - |
| disabled | boolean | false |
"""

        _title, converted = dataset_builder.clean_markdown(source, "select.md")

        self.assertIn(
            "| value | string \\| string[] \\| number \\| number[] | - |",
            converted,
        )
        self.assertNotIn("|<br", converted)
        self.assertEqual(4, sum(1 for line in converted.splitlines() if line.startswith("|")))

    def test_html_break_normalization_preserves_fenced_block_boundaries(self) -> None:
        source = """# Database setup

安装脚本用于初始化数据库。

```shell
setup mysql
```

> **说明:** 数据库应部署在同一管理节点，并使用安全配置。
"""

        _title, converted = dataset_builder.clean_markdown(source, "database.md")

        self.assertIn("安装脚本用于初始化数据库。\n\n```shell", converted)
        self.assertIn("setup mysql\n```\n\n> **说明:**", converted)

    def test_heading_anchors_are_removed_only_for_rendered_format_conversion(self) -> None:
        source = """# Guide {#guide}

## Metadata cache {#410-meta-cache}

```markdown
# Literal {#inside-code}
```
"""

        converted = dataset_builder.strip_heading_anchors(source)

        self.assertIn("# Guide\n", converted)
        self.assertIn("## Metadata cache\n", converted)
        self.assertNotIn("{#guide}", converted)
        self.assertNotIn("{#410-meta-cache}", converted)
        self.assertIn("# Literal {#inside-code}", converted)

    def test_admonition_becomes_block_quote_without_flattening_code(self) -> None:
        source = """# Guide

```python
def is_prime(n):
    return n > 1
```

:::caution 注意
- Keep the code intact.
- Verify every node.
:::

## Continue
"""

        converted = dataset_builder.prepare_markdown_for_pdf_conversion(source)

        self.assertNotIn(":::caution", converted)
        self.assertNotIn("\n:::\n", converted)
        self.assertIn("```python\ndef is_prime(n):\n    return n > 1\n```", converted)
        self.assertIn("> **注意**", converted)
        self.assertIn("> - Keep the code intact.", converted)
        self.assertIn("\n## Continue\n", converted)

    def test_fence_content_that_looks_like_an_admonition_is_unchanged(self) -> None:
        source = """```markdown
:::tip Example
body
:::
```
"""

        converted = dataset_builder.normalize_docusaurus_admonitions(source)

        self.assertEqual(source, converted)


if __name__ == "__main__":
    unittest.main()
