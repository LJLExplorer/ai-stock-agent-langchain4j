/** 每次切换上下文或开始新请求时使旧回调失效，不依赖网络是否成功取消。 */
export function createRequestScope() {
  let generation = 0
  return {
    invalidate() {
      generation += 1
    },
    capture() {
      const captured = generation
      return () => captured === generation
    }
  }
}
