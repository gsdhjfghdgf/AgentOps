import {marked} from 'marked'
import DOMPurify from 'dompurify'

/** Model/document text is content, never a way to load scripts, submit forms or fetch remote images. */
export function renderMarkdown(text:string):string {
 const fragment=DOMPurify.sanitize(marked.parse(text,{async:false,breaks:true}) as string,{
  RETURN_DOM_FRAGMENT:true,
  FORBID_TAGS:['script','style','link','iframe','object','embed','form','input','button','textarea','select','meta','base','svg','math','audio','video'],
  FORBID_ATTR:['style','class','id','name','srcset','autofocus','formaction'],
 })
 for(const image of fragment.querySelectorAll('img')){
  const src=image.getAttribute('src')||''
  if(!src.startsWith('blob:')&&!/^data:image\/(png|jpeg|gif|webp);base64,/i.test(src)||src.length>2_000_000)image.removeAttribute('src')
 }
 for(const link of fragment.querySelectorAll('a')){
  const href=link.getAttribute('href')||''
  if(href.startsWith('#'))continue
  try{const url=new URL(href);if(!['http:','https:'].includes(url.protocol)||url.username||url.password)throw Error();link.setAttribute('rel','noopener noreferrer');link.setAttribute('target','_blank')}
  catch{link.removeAttribute('href')}
 }
 const container=document.createElement('div');container.append(fragment);return container.innerHTML
}
