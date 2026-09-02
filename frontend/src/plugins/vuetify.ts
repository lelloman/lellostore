import 'vuetify/styles'
import '@mdi/font/css/materialdesignicons.css'
import { createVuetify } from 'vuetify'
import * as components from 'vuetify/components'
import * as directives from 'vuetify/directives'

export default createVuetify({
  components,
  directives,
  theme: {
    defaultTheme: 'light',
    themes: {
      light: {
        dark: false,
        colors: {
          background: '#F4F7F4',
          surface: '#FFFFFF',
          'surface-variant': '#E8EFE9',
          primary: '#176B3A',
          'on-primary': '#FFFFFF',
          secondary: '#5C6F62',
          accent: '#D9A72E',
          error: '#BA1A1A',
          info: '#32628D',
          success: '#176B3A',
          warning: '#9A6700',
        },
      },
      dark: {
        dark: true,
        colors: {
          background: '#0D1711',
          surface: '#14221A',
          'surface-variant': '#223329',
          primary: '#66DB91',
          'on-primary': '#00391B',
          secondary: '#B8CCBD',
          accent: '#F2C75C',
          error: '#FFB4AB',
          info: '#A1C9F2',
          success: '#66DB91',
          warning: '#F2C75C',
        },
      },
    },
  },
  defaults: {
    VBtn: {
      variant: 'flat',
      rounded: 'lg',
      style: 'text-transform: none; font-weight: 650; letter-spacing: 0;',
    },
    VCard: {
      elevation: 0,
      rounded: 'xl',
    },
    VTextField: {
      rounded: 'lg',
    },
  },
})
