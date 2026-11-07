/// <reference types="mdui/jsx.en.d.ts" />
import type React from 'react';

type IconProps = React.DetailedHTMLProps<React.HTMLAttributes<HTMLElement>, HTMLElement> & { slot?: string };

declare global {
  namespace React {
    namespace JSX {
      interface IntrinsicElements {
        [icon: `mdui-icon-${string}`]: IconProps;
      }
    }
  }
}
