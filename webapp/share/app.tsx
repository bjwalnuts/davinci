/*
 * <<
 * Davinci
 * ==
 * Copyright (C) 2016 - 2017 EDP
 * ==
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * >>
 */

import 'intersection-observer'

import React from 'react'
import ReactDOM from 'react-dom'
import { Provider } from 'react-redux'
import { ConnectedRouter } from 'connected-react-router'
import history from 'utils/history'

import App from 'share/containers/App'

import { ConfigProvider } from 'antd'
import zh_CN from 'antd/es/locale/zh_CN'
import LanguageProvider from 'app/containers/LanguageProvider'
import { translationMessages } from 'app/i18n'
import moment from 'moment'
import 'moment/locale/zh-cn'
moment.locale('zh-cn')

import '!file-loader?name=[name].[ext]!../app/favicon.ico'
import 'file-loader?name=[name].[ext]!../app/.htaccess'
import 'react-grid-layout/css/styles.css'
import 'libs/react-resizable/css/styles.css'
import 'bootstrap-datepicker/dist/css/bootstrap-datepicker3.standalone.min.css'
import '../app/assets/fonts/iconfont.css'
import '../app/assets/override/antd.css'
import '../app/assets/override/react-grid.css'
import '../app/assets/override/datepicker.css'
import '../app/assets/less/style.less'

import * as echarts from 'echarts/lib/echarts'
import 'zrender/lib/svg/svg'
import 'echarts/lib/chart/bar'
import 'echarts/lib/chart/line'
import 'echarts/lib/chart/scatter'
import 'echarts/lib/chart/pie'
import 'echarts/lib/chart/sankey'
import 'echarts/lib/chart/funnel'
import 'echarts/lib/chart/map'
import 'echarts/lib/chart/lines'
import 'echarts/lib/chart/effectScatter'
import 'echarts/lib/chart/treemap'
import 'echarts/lib/chart/heatmap'
import 'echarts/lib/chart/boxplot'
import 'echarts/lib/chart/graph'
import 'echarts/lib/chart/gauge'
import 'echarts/lib/chart/radar'
import 'echarts/lib/chart/parallel'
import 'echarts/lib/chart/pictorialBar'
import 'echarts-wordcloud'
import 'echarts/lib/component/legend'
import 'echarts/lib/component/legendScroll'
import 'echarts/lib/component/tooltip'
import 'echarts/lib/component/toolbox'
import 'echarts/lib/component/dataZoom'
import 'echarts/lib/component/visualMap'
import 'echarts/lib/component/geo'
import 'echarts/lib/component/brush'
import 'echarts/lib/component/markLine'
import 'echarts/lib/component/markArea'
import '../app/assets/js/china.js'

import { DEFAULT_ECHARTS_THEME } from 'app/globalConstants'
echarts.registerTheme('default', DEFAULT_ECHARTS_THEME)

import configureStore from 'app/configureStore'

const initialState = {}
const store = configureStore(initialState, history)
const MOUNT_NODE = document.getElementById('app')

const render = (messages) => {
  ReactDOM.render(
    <Provider store={store}>
      <LanguageProvider messages={messages}>
        <ConfigProvider locale={zh_CN}>
          <ConnectedRouter history={history}>
            <App />
          </ConnectedRouter>
        </ConfigProvider>
      </LanguageProvider>
    </Provider>,
    MOUNT_NODE
  )
}

if (module.hot) {
  module.hot.accept(['../app/i18n', 'share/containers/App'], () => {
    ReactDOM.unmountComponentAtNode(MOUNT_NODE)
    render(translationMessages)
  })
}

interface IWindow extends Window {
  Intl: any
  __REACT_DEVTOOLS_GLOBAL_HOOK__: any
}
declare const window: IWindow

if (!window.Intl) {
  new Promise((resolve) => {
    resolve(import('intl'))
  })
    .then(() => Promise.all([import('intl/locale-data/jsonp/en.js')]))
    .then(() => render(translationMessages))
    .catch((err) => {
      throw err
    })
} else {
  render(translationMessages)
}

// Install ServiceWorker and AppCache in the end since
// it's not most important operation and if main code fails,
// we do not want it installed
if (process.env.NODE_ENV === 'production') {
  // disable react developer tools in production
  if (window.__REACT_DEVTOOLS_GLOBAL_HOOK__) {
    window.__REACT_DEVTOOLS_GLOBAL_HOOK__.inject = () => void 0
  }
}
