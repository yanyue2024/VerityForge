# 样式兼容

## 默认样式兼容性说明

Ant Design 支持最近 2 个版本的现代浏览器。默认情况下，我们使用了一些现代 CSS 特性来提高样式的可维护性和可扩展性，这些特性在旧版浏览器中可能不被支持，好在我们可以通过一些降级兼容方案来解决。

| 特性 | antd 版本 | 兼容性 | 最低 Chrome 版本 | 降级兼容方案 |
| --- | --- | --- | --- | --- |
| :where 选择器 | `>=5.0.0` | caniuse | Chrome 88 | `` |
| CSS 逻辑属性 | `>=5.0.0` | caniuse | Chrome 89 | `` |

如果你需要兼容旧版浏览器，请根据实际需求使用 `@ant-design/cssinjs` 的 StyleProvider 降级处理。

## `:where` 选择器

- 支持版本：`>=5.0.0`
- MDN 文档：:where
- 浏览器兼容性：caniuse
- Chrome 最低支持版本：88
- 默认启用：是

Ant Design 的 CSS-in-JS 默认通过 `:where` 选择器降低 CSS Selector 优先级，以减少用户升级时额外调整自定义样式的成本，不过 `:where` 语法的兼容性在低版本浏览器比较差。在某些场景下你如果需要支持旧版浏览器，你可以使用 `@ant-design/cssinjs` 取消默认的降权操作（请注意版本保持与 antd 一致）：

```tsx

// `hashPriority` 默认为 `low`，配置为 `high` 后，
// 会移除 `:where` 选择器封装

);
```

切换后，样式将从 `:where` 切换为类选择器：

```diff
--  :where(.css-bAMboO).ant-btn {
++  .css-bAMboO.ant-btn {
      color: #fff;
    }
```

注意：关闭 `:where` 降权后，你可能需要手动调整一些样式的优先级。亦或者**使用 PostCSS 插件**提升应用样式的优先级，PostCSS 提供了非常多的插件来调整优先级，你可以自行按需选择，例如：

- postcss-scopify
- postcss-increase-specificity
- postcss-add-root-selector

通过插件配置，将你的 css 样式进行提升：

```diff
--  .my-btn {
++  #root .my-btn {
      background: red;
    }
```

## CSS 逻辑属性

- 支持版本：`>=5.0.0`
- MDN 文档：CSS Logical Properties
- 浏览器兼容性：caniuse
- Chrome 最低支持版本：89
- 默认启用：是

为了统一 LTR 和 RTL 样式，Ant Design 使用了 CSS 逻辑属性。例如原 `margin-left` 使用 `margin-inline-start` 代替，使其在 LTR 和 RTL 下都为起始位置间距。如果你需要兼容旧版浏览器（如 360 浏览器、QQ 浏览器 等等），可以通过 `@ant-design/cssinjs` 的 `StyleProvider` 配置 `transformers` 将其转换：

```tsx | pure

// `transformers` 提供预处理功能将样式进行转换

);
```

切换后，样式将降级 CSS 逻辑属性：

```diff
.ant-modal-root {
-- inset: 0;
++ top: 0;
++ right: 0;
++ bottom: 0;
++ left: 0;
}
```

## autoPrefixer

- 支持版本：`>=6.0.0`
- 浏览器兼容性：自动添加浏览器前缀以支持更多浏览器
- 默认启用：否

部分样式依赖于浏览器前缀来实现兼容性。`autoPrefixer` 转换器可以自动为样式添加浏览器前缀，确保在不同浏览器中都能正常工作。

```tsx | pure

);
```

最终转换后的样式：

```diff
  .sample-box {
--  user-select: none;
++  -webkit-user-select: none;
++  -moz-user-select: none;
++  -ms-user-select: none;
++  user-select: none;
  }
```

## `@layer` 样式优先级降权

- 支持版本：`>=5.17.0`
- MDN 文档：@layer
- 浏览器兼容性：caniuse
- Chrome 最低支持版本：99
- 默认启用：否

Ant Design 从 `5.17.0` 起支持配置 `layer` 进行统一降权。经过降权后，antd 的样式将始终低于默认的 CSS 选择器优先级，以便于用户进行样式覆盖（请务必注意检查 `@layer` 浏览器兼容性）。StyleProvider 开启 `layer` 时，子元素**必须**包裹 ConfigProvider 以更新图标相关样式：

```tsx | pure

);
```

antd 的样式会被封装在 `@layer` 中，以降低优先级：

```diff
++  @layer antd {
      :where(.css-bAMboO).ant-btn {
        color: #fff;
      }
++  }
```

⚠️ zeroRuntime 场景注意事项（6.0.0 新增）

当你开启 `zeroRuntime` 时，antd 的样式会通过预构建方式产出为 `antd.css`。如果你同时启用了 `@layer` 降权机制，请务必确保 `antd.css` 也被放入同一 layer（例如 `layer(antd)`），否则其权重会高于 StyleProvider 注入的样式，导致降权失效或覆盖顺序异常。

```css
/* global.css / app.css */
@layer theme, base, antd, components, utilities;

/* zeroRuntime 输出的 antd.css 需要手动指定 layer */
@import url(antd.css) layer(antd);
```

如果无法使用 `@import ... layer()` 语法，也可以在构建阶段将其包裹：

```css
@layer antd {
  /* antd.css 内容 */
}
```

## rem 适配

在响应式网页开发中，需要一种方便且灵活的方式来实现页面的适配和响应式设计。`px2remTransformer` 转换器可以快速而准确地将样式表中的像素单位转换为相对于根元素（HTML 标签）的 rem 单位，实现页面的自适应和响应式布局。

```tsx | pure

const px2rem = px2remTransformer({
  rootValue: 32, // 32px = 1rem; @default 16
});

);
```

最终转换后的样式：

```diff
 .px2rem-box {
-  width: 400px;
+  width: 12.5rem;
   background-color: green;
-  font-size: 32px;
+  font-size: 1rem;
   border: 10PX solid #f0f;
 }

 @media only screen and (max-width: 600px) {
   .px2rem-box {
     background-color: red;
-    margin: 10px;
+    margin: 0.3125rem;
   }
 }
```

### 配置项

| 参数 | 说明  | 类型 | 默认值 |
| --- | --- | --- | --- |
| rootValue | 根元素字体大小 | `number` | 16 |
| precision | 转换后的小数点位数 | `number` | 5 |
| mediaQuery | 是否转换媒体查询中的 px | `boolean` | false |

详细请参考: px2rem.ts#Options

## Shadow DOM 场景

在 Shadow DOM 场景中，由于其添加 `

```

#### ✅ 正确的写法

```html

```
