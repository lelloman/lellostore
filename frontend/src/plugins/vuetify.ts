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
          background: '#F9FCF8',
          'on-background': '#191C1A',
          surface: '#F9FCF8',
          'on-surface': '#191C1A',
          'surface-variant': '#DAE5DD',
          'on-surface-variant': '#3F4943',
          primary: '#3DDC84',
          'on-primary': '#003822',
          'primary-readable': '#006C45',
          'primary-container': '#B9FFD2',
          'on-primary-container': '#003822',
          secondary: '#4E6356',
          'on-secondary': '#FFFFFF',
          'secondary-container': '#D1E8D7',
          'on-secondary-container': '#0B1F15',
          accent: '#705D00',
          error: '#BA1A1A',
          'on-error': '#FFFFFF',
          'error-container': '#FFDAD6',
          'on-error-container': '#410002',
          info: '#32628D',
          success: '#006C45',
          warning: '#705D00',
          outline: '#6F7972',
        },
      },
      dark: {
        dark: true,
        colors: {
          background: '#002113',
          'on-background': '#E1E3DF',
          surface: '#002113',
          'on-surface': '#E1E3DF',
          'surface-variant': '#3F4943',
          'on-surface-variant': '#BEC9C1',
          primary: '#3DDC84',
          'on-primary': '#003822',
          'primary-readable': '#43E38B',
          'primary-container': '#005233',
          'on-primary-container': '#62FBA2',
          secondary: '#B5CCBC',
          'on-secondary': '#20352A',
          'secondary-container': '#374B40',
          'on-secondary-container': '#D1E8D7',
          accent: '#E6C44F',
          error: '#FFB4AB',
          'on-error': '#690005',
          'error-container': '#93000A',
          'on-error-container': '#FFDAD6',
          info: '#A1C9F2',
          success: '#43E38B',
          warning: '#E6C44F',
          outline: '#6F7972',
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
