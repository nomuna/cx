window.onload=function(){var t=document.getElementById("logo"),o=document.getElementsByTagName("body")[0];(window.onscroll=window.ontouchmove=function(){var e=t.clientHeight<window.scrollY,n="scroll"==o.getAttribute("class");!e&&n?o.removeAttribute("class"):e&&!n&&o.setAttribute("class","scroll")})(),window.onresize=function(){t.setAttribute("style","font-size: "+o.clientWidth+"px"),window.onscroll()}};