// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
import {Component, Directive} from "@angular/core";

@Component({
    template: `
        
    `
})
export class NoProp {

}

@Component({
    templateUrl: ""
})
export class Prop1 {

}


@Component({
    template: ""
})
export class Prop2 {

}


@Component({
    template: "foo",
    templateUrl: "bar"
})
export class PropBoth {

}

@Directive({

})
export class Dir {

}

@Component({selector: "foo"})
